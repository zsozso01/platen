package io.github.zsozso01.platen.testing.fakeprinter

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.protocol.pjl.Pjl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One PJL environment variable as `@PJL INFO VARIABLES` lists it. */
class FakePjlVariable(val name: String, val current: String, val allowed: List<String>, val range: Boolean = false)

/** A job the fake received: the PJL lines before the data, the data, and what followed. */
class ReceivedPjlJob(
    val pjlLines: List<String>,
    val language: String?,
    val data: ByteArray,
    val ended: Boolean,
) {
    fun setting(name: String): String? = pjlLines.firstOrNull { it.startsWith("@PJL SET $name=") }?.substringAfter("=")?.trim()
}

/**
 * A printer that speaks PJL over a byte channel, like a LaserJet on a USB printer interface or port 9100:
 * it answers `@PJL INFO` queries, honours `@PJL SET`, takes a page language after `@PJL ENTER LANGUAGE=`
 * and, if asked with `USTATUS JOB=ON`, reports the job's start and end.
 */
class FakePjlPrinter(
    val id: String = "Fake LaserJet",
    val languages: List<String> = listOf("PCL", "POSTSCRIPT", "PDF"),
    val variables: List<FakePjlVariable> = defaultVariables(),
    deviceCode: Int = 10001,
    deviceDisplay: String = "00 READY",
    /** False models a printer with PJL disabled (a locked-down managed device): it swallows PJL without a word. */
    val answersPjl: Boolean = true,
    /** False models a unidirectional USB interface: nothing can be read back. */
    val bidirectional: Boolean = true,
    /** If false the printer ignores `USTATUS JOB=ON`, so the host never hears about the job. */
    val honoursUstatus: Boolean = true,
    val pagesPerJob: Int = 3,
) : AutoCloseable {
    /** What `INFO STATUS` reports. Tests change these to model a printer that is busy or needs attention. */
    @Volatile var deviceCode: Int = deviceCode

    @Volatile var deviceDisplay: String = deviceDisplay

    /** False models a printer that reports a job's start but never its end. */
    @Volatile var reportsJobEnd: Boolean = true

    /** True makes the printer stop answering everything, as a printer that has hung or been switched off. */
    @Volatile var muted: Boolean = false

    /** What the next `USTATUS JOB END` says about the page count. */
    @Volatile var reportedPages: Int = pagesPerJob

    /** Number of times a host opened the connection, to check that tidy-up uses a new one. */
    @Volatile var connections: Int = 0
        private set

    val jobs: MutableList<ReceivedPjlJob> = CopyOnWriteArrayList()

    /** Every PJL line seen, for tests that check what was asked. */
    val allPjlLines: MutableList<String> = CopyOnWriteArrayList()

    @Volatile var jobStatusOn = false

    @Volatile var deviceStatusOn = false

    /** Extra status text to push after the job starts (for example a paper-out report). */
    val scriptedMessages = LinkedBlockingQueue<String>()

    private val toHost = LinkedBlockingQueue<ByteArray>()
    private val fromHost = LinkedBlockingQueue<ByteArray>()
    private val stop = AtomicBoolean(false)
    private val worker = thread(name = "fake-pjl-printer", isDaemon = true) { run() }

    /**
     * A new connection from the host. All connections share the printer, as with a real USB interface:
     * bytes the printer has sent and nobody has read stay in the queue for the next one.
     */
    fun open(): ByteChannel {
        connections++
        return object : ByteChannel {
            @Volatile private var closed = false

            override fun write(data: ByteArray, offset: Int, length: Int) {
                if (closed) throw IOException("channel closed")
                fromHost.put(data.copyOfRange(offset, offset + length))
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
                if (!bidirectional) return -1
                if (closed) return -1
                val chunk = toHost.poll(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) ?: return 0
                val n = minOf(length, chunk.size)
                System.arraycopy(chunk, 0, buffer, offset, n)
                if (n < chunk.size) toHost.put(chunk.copyOfRange(n, chunk.size))
                return n
            }

            override fun close() {
                closed = true
            }
        }
    }

    // --- device side ------------------------------------------------------------------------------

    /** Bytes received and not yet consumed. */
    private var pending = ByteArray(0)
    private var inData = false
    private var language: String? = null
    private var jobLines = mutableListOf<String>()
    private var jobName: String? = null
    private var lastData = ByteArray(0)
    private val uel = Pjl.UEL.toByteArray(Charsets.ISO_8859_1)

    private fun run() {
        while (!stop.get()) {
            val chunk = fromHost.poll(50, TimeUnit.MILLISECONDS) ?: continue
            pending += chunk
            process()
        }
    }

    private fun process() {
        while (true) {
            if (inData) {
                // Page data runs until the next UEL.
                val at = indexOf(pending, uel, 0)
                if (at < 0) return
                lastData = pending.copyOfRange(0, at)
                pending = pending.copyOfRange(at + uel.size, pending.size)
                inData = false
            } else {
                while (startsWith(pending, uel, 0)) pending = pending.copyOfRange(uel.size, pending.size)
                val eol = indexOfByte(pending, '\n'.code.toByte(), 0)
                if (eol < 0) return
                val line = String(pending, 0, eol, Charsets.ISO_8859_1).trimEnd('\r')
                pending = pending.copyOfRange(eol + 1, pending.size)
                if (line.isNotBlank()) handle(line)
            }
        }
    }

    private fun handle(line: String) {
        if (!line.startsWith("@PJL")) return
        allPjlLines += line
        jobLines += line
        if (!answersPjl) {
            if (line.startsWith("@PJL ENTER LANGUAGE=")) beginData(line)
            if (line.startsWith("@PJL EOJ")) finishJob(report = false)
            return
        }
        val upper = line.uppercase()
        when {
            upper.startsWith("@PJL INFO ID") -> reply("@PJL INFO ID\r\n\"$id\"\r\n")
            upper.startsWith("@PJL INFO CONFIG") -> reply(configText())
            upper.startsWith("@PJL INFO VARIABLES") -> reply(variablesText())
            upper.startsWith("@PJL INFO STATUS") -> reply("@PJL INFO STATUS\r\nCODE=$deviceCode\r\nDISPLAY=\"$deviceDisplay\"\r\nONLINE=TRUE\r\n")
            upper.startsWith("@PJL ECHO") -> reply("$line\r\n")
            upper.startsWith("@PJL USTATUS JOB=ON") -> jobStatusOn = honoursUstatus
            upper.startsWith("@PJL USTATUS JOB=OFF") -> jobStatusOn = false
            upper.startsWith("@PJL USTATUS DEVICE=ON") -> deviceStatusOn = honoursUstatus
            upper.startsWith("@PJL USTATUS DEVICE=OFF") -> deviceStatusOn = false
            upper.startsWith("@PJL JOB") -> {
                jobName = Regex("NAME=\"([^\"]*)\"").find(line)?.groupValues?.get(1)
                if (jobStatusOn) emit("@PJL USTATUS JOB\r\nSTART\r\nNAME=\"${jobName.orEmpty()}\"\r\n\u000C")
            }
            upper.startsWith("@PJL EOJ") -> finishJob(report = jobStatusOn)
            upper.startsWith("@PJL ENTER LANGUAGE=") -> beginData(line)
        }
    }

    private fun finishJob(report: Boolean) {
        jobs += ReceivedPjlJob(jobLines.toList(), language, lastData, ended = true)
        if (report) {
            if (reportsJobEnd) emit("@PJL USTATUS JOB\r\nEND\r\nNAME=\"${jobName.orEmpty()}\"\r\nPAGES=$reportedPages\r\n\u000C")
        }
        jobLines = mutableListOf()
        language = null
        lastData = ByteArray(0)
    }

    private fun beginData(line: String) {
        language = line.substringAfter("=").trim().uppercase()
        inData = true
        if (answersPjl) drainScripted() // status that arrives while the job is printing
    }

    private fun drainScripted() {
        while (true) emit(scriptedMessages.poll() ?: break)
    }

    private fun reply(text: String) {
        emit(text + "\u000C")
    }

    private fun emit(text: String) {
        if (!muted) toHost.put(text.toByteArray(Charsets.ISO_8859_1))
    }

    /** Puts bytes on the printer's output as if it had sent them earlier (stale data from a previous session). */
    fun pushToHost(text: String) {
        toHost.put(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun configText() = buildString {
        append("@PJL INFO CONFIG\r\n")
        append("LANGUAGES [${languages.size} ENUMERATED]\r\n")
        languages.forEach { append("\t").append(it).append("\r\n") }
    }

    private fun variablesText() = buildString {
        append("@PJL INFO VARIABLES\r\n")
        for (v in variables) {
            append("${v.name}=${v.current} [${v.allowed.size} ${if (v.range) "RANGE" else "ENUMERATED"}]\r\n")
            v.allowed.forEach { append("\t").append(it).append("\r\n") }
        }
    }

    override fun close() {
        stop.set(true)
        worker.join(1_000)
    }

    companion object {
        fun defaultVariables(): List<FakePjlVariable> = listOf(
            FakePjlVariable("COPIES", "1", listOf("1", "999"), range = true),
            FakePjlVariable("QTY", "1", listOf("1", "32767"), range = true),
            FakePjlVariable("DUPLEX", "OFF", listOf("OFF", "ON")),
            FakePjlVariable("BINDING", "LONGEDGE", listOf("LONGEDGE", "SHORTEDGE")),
            FakePjlVariable("PAPER", "A4", listOf("LETTER", "LEGAL", "A4", "A5", "A3", "EXECUTIVE")),
            FakePjlVariable("MEDIASOURCE", "TRAY1", listOf("TRAY1", "TRAY2", "MANUALFEED")),
            FakePjlVariable("OUTBIN", "UPPER", listOf("UPPER", "LOWER")),
            FakePjlVariable("RESOLUTION", "600", listOf("300", "600", "1200")),
            FakePjlVariable("ECONOMODE", "OFF", listOf("OFF", "ON")),
        )

        private fun indexOfByte(bytes: ByteArray, b: Byte, from: Int): Int {
            for (i in from until bytes.size) if (bytes[i] == b) return i
            return -1
        }

        private fun startsWith(bytes: ByteArray, prefix: ByteArray, at: Int): Boolean {
            if (at + prefix.size > bytes.size) return false
            for (i in prefix.indices) if (bytes[at + i] != prefix[i]) return false
            return true
        }

        private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
            var i = from
            while (i + needle.size <= bytes.size) {
                if (startsWith(bytes, needle, i)) return i
                i++
            }
            return -1
        }
    }
}
