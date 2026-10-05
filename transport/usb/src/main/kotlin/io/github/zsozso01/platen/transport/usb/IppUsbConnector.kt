package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.protocol.ipp.IppConnection
import io.github.zsozso01.platen.protocol.ipp.IppConnector
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lends the 2 to 3 IPP-over-USB interfaces of a printer out as IPP connections, one HTTP exchange at a
 * time per interface (what the specification allows and what `ipp-usb` does).
 *
 * A USB pipe is not a socket: closing it does not discard unread bytes, so a response that is not read
 * to its end poisons the next request. Therefore a connection reports whether its response completed
 * ([IppConnection.responseCompleted]); if it is closed without that, the pipe is recovered (drained and
 * soft-reset) before it is lent out again.
 *
 * A reader thread runs for the life of each connection so the printer's answer is picked up even while a
 * large request is still being written (printers may reject a job early, or upgrade to HTTPS).
 */
public class IppUsbConnector(
    pipes: List<UsbIppPipe>,
    /** How long [connect] waits when every interface is busy. */
    private val acquireTimeoutMillis: Long = 30_000,
    /** How long a read may wait with no data at all before the printer is declared silent. */
    private val silenceTimeoutMillis: Long = 120_000,
    /** Reads and the stop check are done in slices of this length. */
    private val sliceMillis: Int = 100,
    private val writeChunkBytes: Int = 16 * 1024,
) : IppConnector {
    private val idle = LinkedBlockingDeque<UsbIppPipe>(pipes)
    private val live = AtomicInteger(pipes.size)

    /** Number of interfaces still usable. */
    public val usablePipes: Int get() = live.get()

    @Throws(IOException::class)
    override fun connect(): IppConnection {
        if (live.get() == 0) throw IOException("The USB printer is not available any more")
        val pipe = idle.pollFirst(acquireTimeoutMillis, TimeUnit.MILLISECONDS)
            ?: throw IOException("All USB interfaces of the printer are busy")
        return UsbIppConnection(pipe)
    }

    private fun giveBack(pipe: UsbIppPipe) {
        idle.addLast(pipe)
    }

    private fun retire(pipe: UsbIppPipe) {
        live.decrementAndGet()
        runCatching(pipe::close)
    }

    private inner class UsbIppConnection(private val pipe: UsbIppPipe) : IppConnection {
        private val received = LinkedBlockingQueue<ByteArray>()

        @Volatile private var closed = false

        @Volatile private var completed = false

        @Volatile private var writing = false

        @Volatile private var pumpFailure: IOException? = null
        private var current: ByteArray = EMPTY
        private var position = 0
        private var lastData = System.nanoTime()

        private val pump = Thread({ pumpLoop() }, "platen-usb-ipp-reader").apply { isDaemon = true; start() }

        private fun pumpLoop() {
            val buffer = ByteArray(8 * 1024)
            try {
                while (!closed) {
                    val n = pipe.read(buffer, 0, buffer.size, sliceMillis)
                    if (n > 0) received.add(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                pumpFailure = e
            }
        }

        override val input: InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (true) {
                    if (position < current.size) {
                        val n = minOf(len, current.size - position)
                        System.arraycopy(current, position, b, off, n)
                        position += n
                        return n
                    }
                    val next = received.poll(sliceMillis.toLong(), TimeUnit.MILLISECONDS)
                    if (next != null) {
                        current = next
                        position = 0
                        lastData = System.nanoTime()
                        continue
                    }
                    pumpFailure?.let { throw IOException("USB read failed: ${it.message}", it) }
                    if (closed) throw IOException("USB connection closed")
                    if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastData) > silenceTimeoutMillis) {
                        throw IOException("The printer did not answer over USB for ${silenceTimeoutMillis / 1000} seconds")
                    }
                }
            }
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var sent = 0
                writing = true
                try {
                    while (sent < len) {
                        if (closed) throw IOException("USB connection closed")
                        pumpFailure?.let { throw IOException("USB failed: ${it.message}", it) }
                        val n = minOf(writeChunkBytes, len - sent)
                        pipe.write(b, off + sent, n)
                        sent += n
                    }
                } finally {
                    writing = false
                }
            }
        }

        override fun responseCompleted() {
            completed = true
        }

        @Synchronized
        override fun close() {
            if (closed) return
            // From another thread (a cancel) while a write is stuck: a soft reset ends the pending transfer.
            val interrupted = writing
            closed = true
            if (interrupted) runCatching(pipe::abort)
            runCatching { pump.join(2_000) }
            val clean = completed && received.isEmpty() && position >= current.size && pumpFailure == null
            if (clean) {
                giveBack(pipe)
            } else if (runCatching(pipe::recover).getOrDefault(false)) {
                giveBack(pipe)
            } else {
                retire(pipe)
            }
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
