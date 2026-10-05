package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobProtocol
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterProbe
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Severity
import io.github.zsozso01.platen.protocol.ieee1284.Ieee1284DeviceId
import io.github.zsozso01.platen.protocol.pjl.Pjl
import io.github.zsozso01.platen.protocol.pjl.PjlJobBuilder
import io.github.zsozso01.platen.protocol.pjl.PjlJobEvent
import io.github.zsozso01.platen.protocol.pjl.PjlQueries
import io.github.zsozso01.platen.protocol.pjl.PjlResponse
import io.github.zsozso01.platen.protocol.pjl.PjlStatus
import io.github.zsozso01.platen.protocol.pjl.PjlVariable
import io.github.zsozso01.platen.protocol.pjl.PjlVariableParser
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * The PJL job protocol: the way classic PDF/PostScript/PCL printers are driven over a USB printer
 * interface or a raw socket. A job is the page data wrapped in PJL, which carries the settings (`@PJL SET`)
 * and asks for status reports; the printer's answers come back on the same channel.
 *
 * Everything here is deliberately forgiving, because printers differ in what they answer: a printer that
 * says nothing is still printed to, a channel that cannot be read is still written, and nothing is
 * claimed that the printer did not say (see the rules in [submit]).
 *
 * @param open opens a fresh channel to the printer. Called once per probe or job, and once more to tidy
 *   up after a cancelled job.
 * @param deviceId the printer's IEEE 1284 Device ID if the transport can read it (USB can; a socket cannot).
 * @param wrapInPjl false sends the document bare ("raw mode"), for printers that choke on PJL. Nothing is
 *   asked and nothing is tracked then.
 * @param ioFailure how a failed transfer is reported; the USB glue maps it to a USB-specific failure.
 * @param trace receives short human-readable lines about the exchange, for the diagnostics export.
 */
public class PjlJobProtocol(
    private val open: () -> ByteChannel,
    private val deviceId: () -> String? = { null },
    private val timing: PjlTiming = PjlTiming(),
    private val wrapInPjl: Boolean = true,
    private val ioFailure: (IOException) -> PrintFailure = { PrintFailure.Unreachable(it) },
    private val trace: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : JobProtocol {
    override val id: String = "pjl"

    @Throws(IOException::class)
    override fun probe(): PrinterProbe {
        val parsedId = deviceId()?.let { Ieee1284DeviceId.parse(it) }
        trace("device id: ${parsedId?.raw ?: "not available"}")
        if (!wrapInPjl) {
            return PrinterProbe(PjlCapabilityMapper.map(parsedId, emptyList(), emptyList()), PrinterState.UNKNOWN, emptyList(), parsedId?.displayName)
        }
        open().use { channel ->
            val link = PjlLink(channel, timing, clock)
            val readable = link.flush()
            val answer = if (readable) link.query(PjlQueries.INFO_ID, PjlQueries.INFO_CONFIG, PjlQueries.INFO_VARIABLES, PjlQueries.INFO_STATUS) else null
            val config = answer?.find("INFO CONFIG")?.let { PjlVariableParser.parse(it) }.orEmpty()
            val variables = answer?.find("INFO VARIABLES")?.let { PjlVariableParser.parse(it) }.orEmpty()
            val status = answer?.find("INFO STATUS")?.let { PjlStatus.from(it) }
            val pjlId = answer?.find("INFO ID")?.text
            trace(
                "probe: readable=$readable, heard=${answer?.heardAnything}, id=$pjlId, config=${config.size} entries, " +
                    "variables=${variables.size} entries, status=${status?.code} ${status?.display}",
            )
            return PrinterProbe(
                capabilities = PjlCapabilityMapper.map(parsedId, config, variables),
                state = PjlIssues.state(status),
                issues = status?.let { PjlIssues.from(it) }.orEmpty(),
                makeAndModel = pjlId ?: parsedId?.displayName,
            )
        }
    }

    /**
     * Sends the job and follows it as far as the printer lets us know. What is reported, in order of how
     * much the printer says:
     *
     *  - It reports job start and end (`USTATUS JOB`): [JobEvent.Completed] when the end report arrives.
     *    An end with 0 pages is a failure, not a success.
     *  - It answers `INFO STATUS` but not job reports: completed once it has said "ready" twice after the
     *    last byte was sent.
     *  - It says nothing (or the channel cannot be read): completed when the last byte has been accepted,
     *    like every classic printer port. This means "sent", and the documentation says so.
     *  - It talked, then went quiet or the connection dropped: [JobEvent.Detached].
     *
     * A cancel while sending closes the connection and ends the job on a fresh one. A job the printer has
     * fully received cannot be recalled by PJL, so then the user is told to use the printer's own button.
     */
    override fun submit(submission: JobSubmission, cancel: CancelToken, listener: (JobEvent) -> Unit) {
        val language = languageFor(submission.format)
        if (wrapInPjl && language == null) {
            listener(JobEvent.Failed(PrintFailure.Rejected("The PJL route cannot send ${submission.format.mime}; it needs PDF, PostScript or PCL")))
            return
        }
        JobRun(submission, language, cancel, listener).execute()
    }

    private fun languageFor(format: DocumentFormat): Pjl.Language? = when {
        format.mime.equals(DocumentFormat.PDF.mime, ignoreCase = true) -> Pjl.Language.PDF
        format.mime.equals(DocumentFormat.POSTSCRIPT.mime, ignoreCase = true) -> Pjl.Language.POSTSCRIPT
        format.mime.equals(DocumentFormat.PCL.mime, ignoreCase = true) -> Pjl.Language.PCL
        else -> null
    }

    private inner class JobRun(
        private val submission: JobSubmission,
        private val language: Pjl.Language?,
        private val cancel: CancelToken,
        private val listener: (JobEvent) -> Unit,
    ) {
        private val total = submission.payload.length()
        private val name = Pjl.sanitizeString(submission.jobName).ifEmpty { "Platen" }
        private var builder: PjlJobBuilder? = null
        private var channel: ByteChannel? = null
        private var reader: PjlReader? = null
        private var accepted = false

        // What the printer has told us.
        private val stream = PjlReportStream()
        private var live = false
        private val buffered = mutableListOf<JobEvent>()
        private var heardAnything = false
        private var lastHeard = 0L
        private var sawJobEvent = false
        private var started = false
        private var ended = false
        private var endedPages: Int? = null
        private var cancelledAtPanel = false
        private var idleReports = 0
        private var problems: List<PrinterIssue> = emptyList()

        fun execute() {
            try {
                listener(JobEvent.Sending(0, total))
                val ch = open()
                channel = ch
                cancel.onCancel { runCatching { ch.close() } }

                val link = PjlLink(ch, timing, clock)
                val bidirectional = wrapInPjl && link.flush()
                val variables = if (bidirectional) readVariables(link) else emptyMap()
                if (bidirectional) reader = PjlReader(ch, timing.readSliceMillis)

                val settings = PjlJobTicket.build(submission.settings, variables).copy(jobName = name, statusReporting = bidirectional)
                val job = PjlJobBuilder(settings).also { builder = it }
                trace("job: ${job.header(language ?: Pjl.Language.PDF).toString(Charsets.US_ASCII).replace(Pjl.UEL, "<UEL>").replace(Regex("NAME=\"[^\"]*\""), "NAME=\"...\"").replace("\r\n", " | ")}")

                if (wrapInPjl) write(ch, job.header(language!!))
                sendPayload(ch)
                if (wrapInPjl) write(ch, job.footer())

                accepted = true
                listener(JobEvent.Accepted(null))
                live = true
                buffered.forEach(listener)
                buffered.clear()

                if (reader == null) finishWithoutStatus() else track(ch)
            } catch (e: CancellationException) {
                cancelled()
            } catch (e: IOException) {
                when {
                    cancel.isCancelled -> cancelled()
                    accepted -> listener(JobEvent.Detached(LOST_CONNECTION))
                    else -> listener(JobEvent.Failed(ioFailure(e)))
                }
            } catch (e: Throwable) {
                listener(JobEvent.Failed(PrintFailure.Unexpected(e)))
            } finally {
                runCatching { reader?.close() }
                runCatching { channel?.close() }
            }
        }

        private fun readVariables(link: PjlLink): Map<String, PjlVariable> {
            val answer = link.query(PjlQueries.INFO_VARIABLES)
            val variables = answer.find("INFO VARIABLES")?.let { PjlVariableParser.parse(it) }.orEmpty()
            trace("pre-job: ${variables.size} variables, heard=${answer.heardAnything}")
            return variables.associateBy { it.name }
        }

        private fun sendPayload(ch: ByteChannel) {
            FileInputStream(submission.payload).use { input ->
                val chunk = ByteArray(timing.writeChunkBytes)
                var sent = 0L
                while (true) {
                    if (cancel.isCancelled) throw CancellationException()
                    val n = input.read(chunk)
                    if (n < 0) break
                    ch.write(chunk, 0, n)
                    sent += n
                    listener(JobEvent.Sending(sent, total))
                    drainInput()
                }
            }
        }

        private fun write(ch: ByteChannel, bytes: ByteArray) = ch.write(bytes, 0, bytes.size)

        // --- following the job -------------------------------------------------------------------

        private fun drainInput() {
            val r = reader ?: return
            while (true) consume(r.take(0) ?: break)
        }

        private fun consume(bytes: ByteArray) {
            heardAnything = true
            lastHeard = clock()
            for (response in stream.feed(bytes)) handle(response)
        }

        private fun emit(event: JobEvent) {
            if (live) listener(event) else buffered += event
        }

        private fun nameMatches(reported: String?): Boolean =
            reported.isNullOrBlank() || reported.startsWith(name, ignoreCase = true) || name.startsWith(reported, ignoreCase = true)

        private fun handle(response: PjlResponse) {
            val event = PjlJobEvent.from(response)
            if (event != null) {
                trace("printer: job ${event.type} pages=${event.pages}") // never the name: it is the document's
                if (!nameMatches(event.name)) return
                sawJobEvent = true
                when (event.type) {
                    PjlJobEvent.Type.START -> if (!started) {
                        started = true
                        emit(JobEvent.Printing(null))
                    }
                    PjlJobEvent.Type.END -> {
                        ended = true
                        endedPages = event.pages
                    }
                    PjlJobEvent.Type.CANCELED -> cancelledAtPanel = true
                }
                return
            }
            val command = response.command.trim().uppercase()
            if (command.startsWith("USTATUS DEVICE") || command.startsWith("INFO STATUS")) {
                onStatus(PjlStatus.from(response), polled = command.startsWith("INFO STATUS"))
            }
        }

        private fun onStatus(status: PjlStatus, polled: Boolean) {
            trace("printer: status ${status.code} ${status.display} online=${status.online}")
            val errors = PjlIssues.from(status).filter { it.severity == Severity.ERROR }
            if (errors.isNotEmpty()) {
                if (errors != problems) emit(JobEvent.Attention(errors))
                problems = errors
            } else if (problems.isNotEmpty()) {
                problems = emptyList()
                emit(JobEvent.Printing(null))
            }
            if (polled) idleReports = if (errors.isEmpty() && PjlIssues.state(status) == PrinterState.IDLE) idleReports + 1 else 0
        }

        private fun track(ch: ByteChannel) {
            val r = reader!!
            if (lastHeard == 0L) lastHeard = clock()
            var lastPoll = clock()
            var unanswered = 0
            while (true) {
                if (cancel.isCancelled) {
                    listener(JobEvent.Detached(CANNOT_RECALL))
                    return
                }
                val chunk = r.take(timing.readSliceMillis.toLong())
                if (chunk != null) {
                    consume(chunk)
                    unanswered = 0
                    drainInput()
                }
                if (ended) {
                    listener(if (endedPages == 0) JobEvent.Failed(PrintFailure.Rejected(ZERO_PAGES)) else JobEvent.Completed)
                    return
                }
                if (cancelledAtPanel) {
                    listener(JobEvent.Canceled)
                    return
                }
                if (r.isFinished) {
                    listener(JobEvent.Detached(LOST_CONNECTION))
                    return
                }
                val now = clock()
                if (heardAnything && now - lastHeard >= timing.maxSilentMillis) {
                    listener(JobEvent.Detached("The job was sent, but the printer stopped answering. It may still print; check the printer."))
                    return
                }
                val every = if (sawJobEvent) timing.statusPollMillis else timing.settleMillis
                if (now - maxOf(lastPoll, lastHeard) >= every) {
                    lastPoll = now
                    unanswered++
                    try {
                        write(ch, PjlQueries.query(PjlQueries.INFO_STATUS))
                    } catch (e: IOException) {
                        listener(if (cancel.isCancelled) JobEvent.Detached(CANNOT_RECALL) else JobEvent.Detached(LOST_CONNECTION))
                        return
                    }
                }
                if (!sawJobEvent && idleReports >= 2) {
                    trace("done: the printer reported ready twice after the job, and sent no job report")
                    listener(JobEvent.Completed)
                    return
                }
                if (!heardAnything && unanswered >= 2) {
                    trace("done: the printer answered nothing; treating the transfer as the end")
                    listener(JobEvent.Completed)
                    return
                }
            }
        }

        /** No way to hear back: everything was accepted by the port, which is all there is to know. */
        private fun finishWithoutStatus() {
            trace("done: channel is write-only")
            listener(JobEvent.Completed)
        }

        // --- cancelling --------------------------------------------------------------------------

        private fun cancelled() {
            if (accepted) {
                listener(JobEvent.Detached(CANNOT_RECALL))
                return
            }
            // The data stopped half way: close the job on a fresh connection so the printer is not left waiting.
            val job = builder
            if (wrapInPjl && job != null) {
                runCatching { open().use { write(it, job.footer()) } }
                trace("cancel: job closed on a new connection")
            }
            listener(JobEvent.Canceled)
        }
    }

    private companion object {
        const val CANNOT_RECALL =
            "The printer already has the whole job and PJL cannot take it back. Use the cancel button on the printer."
        const val LOST_CONNECTION =
            "The connection to the printer closed after the job was sent. It may still be printing; check the printer."
        const val ZERO_PAGES =
            "The printer reported that the job ended with 0 pages. If the document did print, this printer counts pages differently; please report it."
    }
}
