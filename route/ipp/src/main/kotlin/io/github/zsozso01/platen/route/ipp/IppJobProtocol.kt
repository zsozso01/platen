package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobProtocol
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterProbe
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Severity
import io.github.zsozso01.platen.protocol.ipp.IppClient
import io.github.zsozso01.platen.protocol.ipp.IppConnection
import io.github.zsozso01.platen.protocol.ipp.IppConnector
import io.github.zsozso01.platen.protocol.ipp.IppDocument
import io.github.zsozso01.platen.protocol.ipp.IppHttpException
import io.github.zsozso01.platen.protocol.ipp.IppHttpTransport
import io.github.zsozso01.platen.protocol.ipp.IppJob
import io.github.zsozso01.platen.protocol.ipp.IppJobState
import io.github.zsozso01.platen.protocol.ipp.IppOperationException
import io.github.zsozso01.platen.protocol.ipp.IppParseException
import io.github.zsozso01.platen.protocol.ipp.IppStatus
import java.io.FileInputStream
import java.io.IOException

/** Where and how to reach a printer's IPP service. The [connector] decides the carrier: TCP, TLS or USB. */
public class IppEndpoint(
    public val connector: IppConnector,
    /** Value for the HTTP `Host` header: `host[:port]`, or `localhost` over USB. */
    public val hostHeader: String,
    /** HTTP path, e.g. `/ipp/print`. */
    public val path: String,
    /** The `printer-uri` operation attribute, e.g. `ipp://printer.local/ipp/print` or `ipp://localhost/ipp/print`. */
    public val printerUri: String,
    public val userName: String = "platen",
    /**
     * True for IPP-over-USB: the USB interface is the connection, so no `Connection: close` is sent and a
     * response without framing is an error rather than something to wait out.
     */
    public val persistentConnection: Boolean = false,
)

/**
 * The IPP job protocol: probes capabilities, sends a job with `Print-Job`, and follows it with
 * `Get-Job-Attributes` until it ends. Blocking; runs on whichever thread the engine gives it.
 */
public class IppJobProtocol(
    private val endpoint: IppEndpoint,
    private val pollIntervalMillis: Long = 1_000,
    /** Give up tracking (but not the job) after the printer has been silent this long. */
    private val maxSilentMillis: Long = 60_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : JobProtocol {
    override val id: String = "ipp"

    private fun client(connector: IppConnector) = IppClient(
        IppHttpTransport(
            connector = connector,
            host = endpoint.hostHeader,
            path = endpoint.path,
            sendConnectionClose = !endpoint.persistentConnection,
            allowReadToEnd = !endpoint.persistentConnection,
        ),
        endpoint.printerUri,
        endpoint.userName,
    )

    @Throws(IOException::class)
    override fun probe(): PrinterProbe {
        val attrs = client(endpoint.connector).getPrinterAttributes()
        val state = IppIssues.state(attrs.state)
        return PrinterProbe(
            capabilities = IppCapabilityMapper.map(attrs),
            state = state,
            issues = IppIssues.reasons(attrs.stateReasons, stopped = state == PrinterState.STOPPED),
            makeAndModel = attrs.makeAndModel,
        )
    }

    override fun submit(submission: JobSubmission, cancel: CancelToken, listener: (JobEvent) -> Unit) {
        val tracker = TrackingConnector(endpoint.connector)
        cancel.onCancel { tracker.abort() }
        val client = client(tracker)
        val total = submission.payload.length()
        listener(JobEvent.Sending(0, total))

        val job = try {
            sendJob(client, submission, total, listener)
        } catch (e: Throwable) {
            listener(JobEvent.Failed(failureOf(e, submission)).takeUnless { cancel.isCancelled } ?: JobEvent.Canceled)
            return
        }
        listener(JobEvent.Accepted(job.id.toString()))
        follow(client(endpoint.connector), job, cancel, listener)
    }

    /** Sends the job. If the printer names attributes it cannot accept, the job is retried once without them. */
    private fun sendJob(client: IppClient, submission: JobSubmission, total: Long, listener: (JobEvent) -> Unit): IppJob {
        var template = IppJobTicket.build(submission.settings)
        var retried = false
        while (true) {
            try {
                FileInputStream(submission.payload).use { input ->
                    return client.printJob(
                        documentFormat = submission.format.mime,
                        jobName = submission.jobName,
                        jobTemplate = template,
                        document = IppDocument(input, total),
                        onBytesSent = { sent -> listener(JobEvent.Sending(sent, total)) },
                    )
                }
            } catch (e: IppOperationException) {
                val rejected = e.unsupportedAttributes.map { it.name }.toSet()
                if (!retried && e.status == IppStatus.CLIENT_ERROR_ATTRIBUTES_OR_VALUES_NOT_SUPPORTED && rejected.isNotEmpty()) {
                    val reduced = IppJobTicket.withoutAttributes(template, rejected)
                    if (reduced.size < template.size) {
                        template = reduced
                        retried = true
                        continue
                    }
                }
                throw e
            }
        }
    }

    // --- following the job ----------------------------------------------------------------------

    private fun follow(client: IppClient, job: IppJob, cancel: CancelToken, listener: (JobEvent) -> Unit) {
        var silentSince: Long? = null
        var lastState: Int? = job.state
        var lastSheets: Int? = null
        var polls = 0
        while (true) {
            if (cancel.isCancelled) {
                runCatching { client.cancelJob(job.id) }
                listener(JobEvent.Canceled)
                return
            }
            sleepUnlessCancelled(pollIntervalMillis, cancel)
            if (cancel.isCancelled) continue

            val status = try {
                client.getJobAttributes(job.id).also { silentSince = null }
            } catch (e: IOException) {
                // Many printers ignore status requests while printing. That is not a failed job.
                val now = clock()
                val since = silentSince ?: now.also { silentSince = it }
                if (now - since >= maxSilentMillis) {
                    listener(JobEvent.Detached("The job was sent, but the printer stopped answering. It may still print; check the printer."))
                    return
                }
                continue
            } catch (e: IppOperationException) {
                // Some printers forget finished jobs quickly: "not found" after we saw it run means it ended.
                if (e.status == IppStatus.CLIENT_ERROR_NOT_FOUND && lastState != null && lastState != IppJobState.PENDING) {
                    listener(JobEvent.Completed)
                    return
                }
                listener(JobEvent.Failed(failureOf(e, null)))
                return
            }

            polls++
            when (status.state) {
                IppJobState.COMPLETED -> {
                    listener(JobEvent.Completed)
                    return
                }
                IppJobState.CANCELED -> {
                    listener(JobEvent.Canceled)
                    return
                }
                IppJobState.ABORTED -> {
                    listener(JobEvent.Failed(abortedFailure(status.stateReasons, status.stateMessage)))
                    return
                }
                IppJobState.PROCESSING -> {
                    if (lastState != IppJobState.PROCESSING || status.mediaSheetsCompleted != lastSheets) {
                        listener(JobEvent.Printing(status.mediaSheetsCompleted))
                    }
                    lastSheets = status.mediaSheetsCompleted
                }
                IppJobState.PROCESSING_STOPPED -> listener(JobEvent.Attention(printerIssues(client)))
                else -> Unit
            }
            lastState = status.state
            // Every third poll, also ask the printer itself: a stopped printer (no paper) can leave a job "pending".
            if (polls % 3 == 0 && status.state != IppJobState.PROCESSING_STOPPED) {
                val issues = printerIssues(client).filter { it.severity == Severity.ERROR }
                if (issues.isNotEmpty()) listener(JobEvent.Attention(issues))
            }
        }
    }

    private fun printerIssues(client: IppClient): List<PrinterIssue> = try {
        val attrs = client.getPrinterAttributes(listOf("printer-state", "printer-state-reasons", "printer-state-message"))
        IppIssues.reasons(attrs.stateReasons, stopped = IppIssues.state(attrs.state) == PrinterState.STOPPED)
    } catch (e: IOException) {
        emptyList()
    }

    private fun sleepUnlessCancelled(millis: Long, cancel: CancelToken) {
        var left = millis
        while (left > 0 && !cancel.isCancelled) {
            val slice = minOf(left, 100)
            sleeper(slice)
            left -= slice
        }
    }

    // --- failures ---------------------------------------------------------------------------------

    private fun failureOf(e: Throwable, submission: JobSubmission?): PrintFailure = when (e) {
        is IppHttpException -> when {
            e.needsAuthentication -> PrintFailure.AuthenticationRequired(e)
            e.needsTls -> PrintFailure.EncryptionRequired(e)
            e.status == 503 -> PrintFailure.NeedsAttention(listOf(PrinterIssue(PrinterIssue.Kind.BUSY, Severity.ERROR, "http-503")), e)
            else -> PrintFailure.Rejected("The printer answered HTTP ${e.status} ${e.reason}".trim(), e)
        }
        is IppOperationException -> when (e.status) {
            IppStatus.CLIENT_ERROR_NOT_AUTHENTICATED, IppStatus.CLIENT_ERROR_NOT_AUTHORIZED, IppStatus.CLIENT_ERROR_FORBIDDEN ->
                PrintFailure.AuthenticationRequired(e)
            IppStatus.CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED ->
                PrintFailure.Rejected("The printer does not accept ${submission?.format?.mime ?: "this document format"}", e)
            IppStatus.CLIENT_ERROR_ATTRIBUTES_OR_VALUES_NOT_SUPPORTED, IppStatus.CLIENT_ERROR_CONFLICTING_ATTRIBUTES ->
                PrintFailure.Rejected("The printer rejected these settings: ${e.unsupportedAttributes.joinToString { it.name }.ifEmpty { "unspecified" }}", e)
            IppStatus.SERVER_ERROR_BUSY, IppStatus.SERVER_ERROR_SERVICE_UNAVAILABLE, IppStatus.SERVER_ERROR_TEMPORARY_ERROR ->
                PrintFailure.NeedsAttention(listOf(PrinterIssue(PrinterIssue.Kind.BUSY, Severity.ERROR, IppStatus.name(e.status), e.statusMessage)), e)
            IppStatus.SERVER_ERROR_NOT_ACCEPTING_JOBS ->
                PrintFailure.NeedsAttention(listOf(PrinterIssue(PrinterIssue.Kind.OFFLINE, Severity.ERROR, IppStatus.name(e.status), e.statusMessage)), e)
            else -> PrintFailure.Rejected("${IppStatus.name(e.status)}${e.statusMessage?.let { ": $it" }.orEmpty()}", e)
        }
        is IppParseException -> PrintFailure.Rejected("The printer sent a reply that Platen cannot read", e)
        is IOException -> PrintFailure.Unreachable(e)
        else -> PrintFailure.Unexpected(e)
    }

    private fun abortedFailure(reasons: List<String>, message: String?): PrintFailure {
        val detail = (reasons.filter { it != "none" } + listOfNotNull(message)).joinToString("; ")
        val formatProblem = reasons.any { it == "document-format-error" || it == "document-unprintable-error" || it == "unsupported-attributes-or-values" }
        return if (formatProblem) {
            PrintFailure.Rejected("The printer could not print the document ($detail)")
        } else {
            PrintFailure.Rejected("The printer aborted the job${if (detail.isEmpty()) "" else " ($detail)"}")
        }
    }
}

/** Remembers the connection currently in use so a cancel can close it from another thread. */
internal class TrackingConnector(private val base: IppConnector) : IppConnector {
    @Volatile
    private var current: IppConnection? = null

    override fun connect(): IppConnection = base.connect().also { current = it }

    fun abort() {
        runCatching { current?.close() }
    }
}
