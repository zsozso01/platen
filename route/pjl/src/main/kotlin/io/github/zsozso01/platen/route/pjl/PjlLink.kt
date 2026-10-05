package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.protocol.pjl.Pjl
import io.github.zsozso01.platen.protocol.pjl.PjlQueries
import io.github.zsozso01.platen.protocol.pjl.PjlResponse
import io.github.zsozso01.platen.protocol.pjl.PjlResponseParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Timing of the PJL route. The defaults suit real printers; tests shrink them. */
public data class PjlTiming(
    /** How long one read waits; also how quickly a close or cancel is noticed. */
    val readSliceMillis: Int = 100,
    /** How long to wait for leftover bytes from an earlier session before starting. */
    val flushMillis: Int = 40,
    /** A printer that has not said a word this long after a query is treated as not speaking PJL. */
    val queryFirstByteMillis: Long = 3_000,
    /** Longest wait for the whole answer to a query. */
    val queryTotalMillis: Long = 10_000,
    /** While tracking a job, how long the printer may be quiet before it is asked for its status. */
    val statusPollMillis: Long = 5_000,
    /** Without job reports from the printer: how long after the last byte before asking, and between asks. */
    val settleMillis: Long = 3_000,
    /** Give up following the job after the printer has been silent this long. */
    val maxSilentMillis: Long = 60_000,
    val writeChunkBytes: Int = 16 * 1024,
)

/** What a query got back. */
internal class PjlAnswer(val responses: List<PjlResponse>, val heardAnything: Boolean, val channelEnded: Boolean) {
    fun find(command: String): PjlResponse? = responses.firstOrNull { normalise(it.command) == command }

    private fun normalise(command: String) = command.trim().uppercase().replace(Regex("\\s+"), " ")
}

/** Request/response conversation on a channel, used before a job and for probing. */
internal class PjlLink(private val channel: ByteChannel, private val timing: PjlTiming, private val clock: () -> Long) {
    /**
     * Discards what an earlier session left unread. Returns false when the channel cannot be read at all
     * (a unidirectional interface), true otherwise.
     */
    @Throws(IOException::class)
    fun flush(): Boolean {
        val buffer = ByteArray(4096)
        var discarded = 0
        while (discarded < MAX_FLUSH_BYTES) {
            val n = channel.read(buffer, 0, buffer.size, timing.flushMillis)
            if (n < 0) return false
            if (n == 0) return true
            discarded += n
        }
        return true
    }

    /** Sends the commands and waits for the echoed marker that says the printer has dealt with all of them. */
    @Throws(IOException::class)
    fun query(vararg commands: String): PjlAnswer {
        val request = PjlQueries.query(*commands)
        channel.write(request, 0, request.size)
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        val start = clock()
        var ended = false
        while (true) {
            val elapsed = clock() - start
            if (elapsed >= timing.queryTotalMillis) break
            if (received.size() == 0 && elapsed >= timing.queryFirstByteMillis) break
            val n = channel.read(buffer, 0, buffer.size, timing.readSliceMillis)
            if (n < 0) {
                ended = true
                break
            }
            if (n > 0) {
                received.write(buffer, 0, n)
                if (PjlResponseParser.isComplete(received.toByteArray())) break
            }
        }
        val bytes = received.toByteArray()
        return PjlAnswer(PjlResponseParser.parse(bytes), heardAnything = bytes.isNotEmpty(), channelEnded = ended)
    }

    private companion object {
        const val MAX_FLUSH_BYTES = 256 * 1024
    }
}

/**
 * Reads a channel on its own thread while a job is being written, so the printer's status messages never
 * back up and stall the transfer. The consumer polls [take]; all events are produced on the consumer's
 * thread, never on this one.
 */
internal class PjlReader(private val channel: ByteChannel, private val sliceMillis: Int) : AutoCloseable {
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    var ended: Boolean = false
        private set

    @Volatile
    var failure: IOException? = null
        private set

    @Volatile
    private var stopped = false

    private val worker = thread(name = "platen-pjl-reader", isDaemon = true) { pump() }

    private fun pump() {
        val buffer = ByteArray(4096)
        try {
            while (!stopped) {
                val n = channel.read(buffer, 0, buffer.size, sliceMillis)
                if (n < 0) break
                if (n > 0) queue.put(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            if (!stopped) failure = e
        } finally {
            ended = true
        }
    }

    /** True when nothing more will ever arrive. */
    val isFinished: Boolean get() = ended && queue.isEmpty()

    /** Waits up to [timeoutMillis] for the next chunk, or returns null. */
    fun take(timeoutMillis: Long): ByteArray? =
        if (timeoutMillis <= 0) queue.poll() else queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    override fun close() {
        stopped = true
        worker.join(sliceMillis * 3L + 500)
    }
}

/** Splits the printer's stream into form-feed-terminated reports, keeping a partial one for the next chunk. */
internal class PjlReportStream {
    private val text = StringBuilder()

    fun feed(bytes: ByteArray): List<PjlResponse> {
        text.append(String(bytes, Charsets.ISO_8859_1))
        val last = text.lastIndexOf(Pjl.RESPONSE_TERMINATOR.toString())
        if (last < 0) {
            if (text.length > MAX_PARTIAL) text.delete(0, text.length - MAX_PARTIAL)
            return emptyList()
        }
        val complete = text.substring(0, last + 1)
        text.delete(0, last + 1)
        return PjlResponseParser.parse(complete)
    }

    private companion object {
        const val MAX_PARTIAL = 64 * 1024
    }
}
