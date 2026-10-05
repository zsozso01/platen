package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.protocol.ieee1284.Ieee1284DeviceId
import java.io.Closeable
import java.io.IOException

/**
 * The handful of operations a platform's USB stack has to provide for an open device. Android's
 * `UsbDeviceConnection` maps onto it one to one (see `AndroidUsbHost` in `platform:usb`); tests use a
 * simulated host. Everything about *how* a printer is driven lives above this interface, in plain Kotlin.
 */
public interface UsbHostConnection : Closeable {
    /**
     * Transfers up to [length] bytes on the bulk endpoint at [endpointAddress]. Returns the count, or -1 if
     * the transfer failed **or timed out**: the platform does not say which, and a timeout loses the count
     * of what did transfer. Callers therefore work in whole packets on reads, and treat any failure that
     * takes much less than the timeout as an error rather than a timeout.
     */
    public fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int

    /** A control transfer on endpoint 0. Returns the byte count or a negative number on failure. */
    public fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int

    /** Claims the interface (taking it from any other driver). False if it could not be claimed. */
    public fun claimInterface(iface: UsbInterfaceInfo): Boolean

    public fun releaseInterface(iface: UsbInterfaceInfo): Boolean

    /** Selects the alternate setting [iface] describes. The interface must be claimed. */
    public fun selectAlternateSetting(iface: UsbInterfaceInfo): Boolean
}

/**
 * A [UsbPrinterAccess] built on a [UsbHostConnection]. It follows the rules that real printers and the
 * Android stack force on a host:
 *
 *  * an interface is claimed first, then its alternate setting is selected (each alternate is a separate
 *    descriptor, and IPP over USB is often not alternate 0);
 *  * reads are one packet at a time with short timeouts, so a timeout can never swallow data;
 *  * a write that fails or stalls is an error, because the platform loses how much of it went through;
 *  * an exchange cut short is repaired with a drain and the class `SOFT_RESET` request;
 *  * the Device ID is read with the class request `GET_DEVICE_ID`, which needs no claimed interface.
 *
 * @param writeTimeoutMillis how long one chunk may stay unaccepted before the printer is declared stuck.
 *   Long on purpose: a printer that is out of paper stops reading, and a timeout ends the job.
 */
public class HostUsbPrinterAccess(
    override val device: UsbDeviceInfo,
    private val usb: UsbHostConnection,
    private val writeTimeoutMillis: Int = 120_000,
    private val writeChunkBytes: Int = 16 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) : UsbPrinterAccess {
    @Volatile
    private var detached = false

    /** Called by the platform when the device is unplugged: every later transfer fails at once. */
    public fun markDetached() {
        detached = true
    }

    @Throws(IOException::class)
    override fun openIppPipes(plan: UsbPrinterPlan.IppUsb): List<UsbIppPipe> {
        val pipes = ArrayList<UsbIppPipe>()
        try {
            for (iface in plan.interfaces) pipes += HostIppPipe(claim(iface))
        } catch (e: IOException) {
            pipes.forEach { runCatching(it::close) }
            throw e
        }
        return pipes
    }

    @Throws(IOException::class)
    override fun openLegacy(plan: UsbPrinterPlan.Legacy): ByteChannel {
        val pipe = HostBulkPipe(claim(plan.iface))
        return UsbLegacyChannel(pipe, pipe.takeIf { plan.iface.bulkIn != null }, onClose = pipe::release)
    }

    override fun readDeviceId(iface: UsbInterfaceInfo): String? {
        if (detached) return null
        val buffer = ByteArray(DEVICE_ID_BUFFER)
        // wValue is the configuration index; many printers ignore it, so try the plain form too.
        for (value in listOf(iface.configurationIndex, 0).distinct()) {
            val n = runCatching {
                usb.controlTransfer(REQUEST_GET_DEVICE_ID_TYPE, REQUEST_GET_DEVICE_ID, value, (iface.interfaceNumber shl 8) or iface.alternateSetting, buffer, 0, buffer.size, CONTROL_TIMEOUT)
            }.getOrDefault(-1)
            if (n >= 2) {
                val id = Ieee1284DeviceId.parse(buffer.copyOf(n))
                if (id.fields.isNotEmpty()) return id.raw
            }
        }
        return null
    }

    override fun close() {
        detached = true
        runCatching(usb::close)
    }

    // --- claiming ---------------------------------------------------------------------------------

    private fun claim(iface: UsbInterfaceInfo): Claimed {
        if (detached) throw IOException("The printer was unplugged")
        if (!usb.claimInterface(iface)) {
            throw IOException("Could not claim USB interface ${iface.interfaceNumber}; another app or the system may be using the printer")
        }
        if (!usb.selectAlternateSetting(iface)) {
            runCatching { usb.releaseInterface(iface) }
            throw IOException("Could not select alternate setting ${iface.alternateSetting} of USB interface ${iface.interfaceNumber}")
        }
        return Claimed(iface)
    }

    private inner class Claimed(val iface: UsbInterfaceInfo) {
        val out: UsbEndpointInfo? get() = iface.bulkOut
        val input: UsbEndpointInfo? get() = iface.bulkIn
    }

    private open inner class HostBulkPipe(protected val claim: Claimed) : UsbBulkPipe {
        @Volatile
        private var released = false

        override fun write(data: ByteArray, offset: Int, length: Int) {
            val endpoint = claim.out ?: throw IOException("This USB interface cannot be written to")
            var position = offset
            val end = offset + length
            var stalledTries = 0
            while (position < end) {
                ensureOpen()
                val count = minOf(writeChunkBytes, end - position)
                val started = clock()
                val done = usb.bulkTransfer(endpoint.address, data, position, count, writeTimeoutMillis)
                when {
                    done > 0 -> {
                        position += done
                        stalledTries = 0
                    }
                    done < 0 -> throw IOException(
                        if (clock() - started < writeTimeoutMillis / 2) "The USB write failed" else "The printer stopped accepting data for ${writeTimeoutMillis / 1000} seconds",
                    )
                    else -> if (++stalledTries >= MAX_EMPTY_WRITES) throw IOException("The printer accepted no data")
                }
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
            val endpoint = claim.input ?: return 0
            ensureOpen()
            // One packet at most: the packet either arrives whole or not at all, so a timeout loses nothing.
            val count = minOf(length, endpoint.maxPacketSize.coerceAtLeast(MIN_PACKET))
            val started = clock()
            val n = usb.bulkTransfer(endpoint.address, buffer, offset, count, timeoutMillis)
            if (n >= 0) return n
            // Failure and timeout look alike; a timeout takes the whole timeout, a real failure does not.
            if (clock() - started < timeoutMillis / 2) {
                ensureOpen()
                throw IOException("The USB read failed")
            }
            return 0
        }

        fun ensureOpen() {
            if (detached) throw IOException("The printer was unplugged")
            if (released) throw IOException("The USB interface was released")
        }

        /** Releases the interface once. Safe to call from another thread, for example to cancel. */
        fun release() {
            if (released) return
            released = true
            runCatching { usb.releaseInterface(claim.iface) }
        }

        override fun close() = release()

        /** The printer class `SOFT_RESET`: tells the printer to drop what it is doing and flush its buffers. */
        fun softReset(): Boolean {
            for (type in SOFT_RESET_TYPES) {
                val r = runCatching { usb.controlTransfer(type, REQUEST_SOFT_RESET, 0, claim.iface.interfaceNumber, null, 0, 0, CONTROL_TIMEOUT) }.getOrDefault(-1)
                if (r >= 0) return true
            }
            return false
        }

        /** Reads and discards until the printer has nothing more to say. */
        fun drain() {
            val sink = ByteArray(MIN_PACKET.coerceAtLeast(claim.input?.maxPacketSize ?: 0))
            val deadline = clock() + DRAIN_BUDGET_MILLIS
            var discarded = 0
            while (clock() < deadline && discarded < DRAIN_LIMIT_BYTES) {
                val n = read(sink, 0, sink.size, DRAIN_SLICE_MILLIS)
                if (n == 0) return
                discarded += n
            }
        }
    }

    private inner class HostIppPipe(claim: Claimed) : HostBulkPipe(claim), UsbIppPipe {
        override fun recover(): Boolean = try {
            drain()
            softReset()
            drain()
            // A reset can drop the alternate setting on some printers; selecting it again is harmless.
            usb.selectAlternateSetting(claim.iface)
            true
        } catch (e: IOException) {
            false
        }

        override fun abort() {
            softReset()
        }
    }

    private companion object {
        const val DEVICE_ID_BUFFER = 1024
        const val CONTROL_TIMEOUT = 2_000
        const val REQUEST_GET_DEVICE_ID_TYPE = 0xA1
        const val REQUEST_GET_DEVICE_ID = 0
        const val REQUEST_SOFT_RESET = 2

        /** Class request to the "other" recipient as the specification says; some printers want the interface. */
        val SOFT_RESET_TYPES = intArrayOf(0x23, 0x21)
        const val MIN_PACKET = 64
        const val MAX_EMPTY_WRITES = 3
        const val DRAIN_SLICE_MILLIS = 50
        const val DRAIN_BUDGET_MILLIS = 3_000L
        const val DRAIN_LIMIT_BYTES = 1 shl 20
    }
}
