package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import java.io.Closeable
import java.io.IOException

/**
 * One direction pair of bulk endpoints on a claimed interface. Blocking. The Android implementation
 * wraps `UsbDeviceConnection.bulkTransfer`.
 */
public interface UsbBulkPipe : Closeable {
    /** Writes all of [length] bytes, blocking while the printer's buffer is full. Throws if the device is gone. */
    @Throws(IOException::class)
    public fun write(data: ByteArray, offset: Int, length: Int)

    /**
     * Reads at most [length] bytes. Returns the count (more than 0) or 0 if nothing arrived within
     * [timeoutMillis]. A zero-length packet also returns 0: it is never an end of data. Throws if the device is gone.
     */
    @Throws(IOException::class)
    public fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int
}

/** A pipe carrying IPP-over-USB, which can be brought back to a known state after an exchange was cut short. */
public interface UsbIppPipe : UsbBulkPipe {
    /**
     * Restores a pipe whose request or response was not completed: discard pending data, send the class
     * SOFT_RESET, select the alternate setting again. Returns false if the pipe cannot be used any more.
     */
    public fun recover(): Boolean

    /** Best-effort interruption of a transfer blocked in another thread (a soft reset ends pending transfers). */
    public fun abort() {}
}

/** A [ByteChannel] over a classic printer interface. */
public class UsbLegacyChannel(
    private val out: UsbBulkPipe,
    /** Null for a unidirectional interface: nothing can be read back. */
    private val input: UsbBulkPipe?,
    private val onClose: () -> Unit = {},
) : ByteChannel {
    @Volatile
    private var closed = false

    override fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) throw IOException("USB channel closed")
        out.write(data, offset, length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        val pipe = input ?: return -1
        if (closed) return -1
        return pipe.read(buffer, offset, length, timeoutMillis)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching(onClose)
    }
}
