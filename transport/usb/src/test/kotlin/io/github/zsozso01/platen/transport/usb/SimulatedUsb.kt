package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.concurrent.thread

/** A blocking in-memory byte queue: one direction of a USB pipe. */
class BytePipe {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var data = ByteArray(0)
    private var closed = false

    val size: Int get() = lock.withLock { data.size }

    fun write(b: ByteArray, off: Int, len: Int) = lock.withLock {
        if (closed) throw IOException("pipe closed")
        data += b.copyOfRange(off, off + len)
        changed.signalAll()
    }

    /** Waits up to [timeoutMillis] for data. Returns the count, 0 on timeout, -1 if closed and empty. */
    fun read(buf: ByteArray, off: Int, len: Int, timeoutMillis: Long): Int = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (data.isEmpty()) {
            if (closed) return -1
            if (left <= 0) return 0
            left = changed.awaitNanos(left)
        }
        val n = minOf(len, data.size)
        System.arraycopy(data, 0, buf, off, n)
        data = data.copyOfRange(n, data.size)
        n
    }

    fun close() = lock.withLock {
        closed = true
        changed.signalAll()
    }
}

/**
 * A printer with IPP-over-USB interfaces, simulated closely enough to catch the mistakes that matter on
 * hardware: every interface is a persistent connection, a response that is not read to its end stays in
 * the pipe and corrupts the next exchange, and a soft reset is the way out.
 */
class SimulatedIppUsbDevice(private val printer: FakeIppPrinter, interfaces: Int = 2) : AutoCloseable {
    val pipes: List<SimPipe> = List(interfaces) { SimPipe(it) }

    inner class SimPipe(val index: Int) : UsbIppPipe {
        @Volatile private var toDevice = BytePipe()

        @Volatile private var toHost = BytePipe()
        private var device: Thread? = null

        val recoveries = AtomicInteger()
        val aborts = AtomicInteger()

        @Volatile var recoveryWorks = true

        /** When set, host writes block (the printer's buffer is "full") until [abort] or [recover]. */
        @Volatile var blockWrites = false

        @Volatile var closed = false

        /** Sleep per host write call, to make a transfer slow enough to overlap with other work. */
        @Volatile var writeDelayMillis = 0L
        val writeCalls = AtomicInteger()

        /** Bytes the printer sent that the host has not read. After a clean exchange this is 0. */
        val staleBytesForHost: Int get() = toHost.size

        init {
            startDevice()
        }

        private fun startDevice() {
            val pipeIn = toDevice
            val pipeOut = toHost
            device = thread(name = "sim-usb-device-$index", isDaemon = true) {
                val input = object : InputStream() {
                    override fun read(): Int {
                        val one = ByteArray(1)
                        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        while (true) {
                            val n = pipeIn.read(b, off, len, 50)
                            if (n != 0) return n // data, or -1 once the queue was closed by a reset
                        }
                    }
                }
                val output = object : OutputStream() {
                    override fun write(b: Int) = pipeOut.write(byteArrayOf(b.toByte()), 0, 1)

                    override fun write(b: ByteArray, off: Int, len: Int) = pipeOut.write(b, off, len)
                }
                printer.serve(input, output, keepAlive = true)
            }
        }

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (blockWrites) {
                if (closed || System.nanoTime() > deadline) throw IOException("write interrupted")
                Thread.sleep(5)
            }
            if (closed) throw IOException("closed")
            writeCalls.incrementAndGet()
            if (writeDelayMillis > 0) Thread.sleep(writeDelayMillis)
            toDevice.write(data, offset, length)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            if (closed) throw IOException("closed")
            val n = toHost.read(buffer, offset, length, timeoutMillis.toLong())
            return if (n < 0) 0 else n // a queue closed by a reset just has nothing more to say
        }

        override fun recover(): Boolean {
            recoveries.incrementAndGet()
            if (!recoveryWorks) return false
            blockWrites = false
            // A soft reset flushes both directions and the device forgets any half-received request.
            toDevice.close()
            toHost.close()
            device?.join(2_000)
            toDevice = BytePipe()
            toHost = BytePipe()
            startDevice()
            return true
        }

        override fun abort() {
            aborts.incrementAndGet()
            blockWrites = false
        }

        override fun close() {
            closed = true
            toDevice.close()
            toHost.close()
        }
    }

    override fun close() = pipes.forEach { it.close() }
}
