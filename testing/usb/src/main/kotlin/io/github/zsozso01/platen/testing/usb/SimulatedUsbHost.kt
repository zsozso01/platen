package io.github.zsozso01.platen.testing.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import io.github.zsozso01.platen.transport.usb.UsbHostConnection
import io.github.zsozso01.platen.transport.usb.UsbInterfaceInfo
import io.github.zsozso01.platen.transport.usb.UsbPrinterPlan
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A USB stack with a printer behind it, simulated at the level the Android API works at: claim an
 * interface, select its alternate setting, move bulk packets, send control requests. It is as unforgiving
 * as the real thing about the points that matter:
 *
 *  * a transfer on an interface that is not claimed, or whose alternate setting was not selected, fails at once;
 *  * a read into a buffer smaller than the packet the printer wants to send fails (babble), so only
 *    packet-sized reads work, and at most one packet is returned per read;
 *  * a read or write that times out returns -1 after the whole timeout, indistinguishable from an error
 *    except by how long it took;
 *  * after [detach] every call fails immediately.
 */
class SimulatedUsbHost(
    val device: UsbDeviceInfo,
    private val ipp: FakeIppPrinter? = null,
    private val pjl: FakePjlPrinter? = null,
    private val deviceIdText: String? = null,
    private val packetSize: Int = 512,
) : UsbHostConnection {
    /** Everything the host asked for, in order, e.g. `claim 0`, `alt 0/1`, `control 0x23 req 2 idx 0`. */
    val log = CopyOnWriteArrayList<String>()

    @Volatile var detached = false
        private set

    /** Make `claimInterface` fail for this interface number, as when another driver owns it. */
    @Volatile var claimFailsFor: Int? = null

    /** Which `SOFT_RESET` request types the printer accepts; the others stall. */
    @Volatile var softResetTypes: Set<Int> = setOf(0x23, 0x21)

    @Volatile var deviceIdLittleEndian = false

    /** A printer that has stopped reading: writes hang until the timeout. */
    @Volatile var stallWrites = false

    val softResets = AtomicInteger()
    val maxReadRequest = AtomicInteger()
    val releases = ConcurrentHashMap<Int, AtomicInteger>()

    private val claimed = ConcurrentHashMap.newKeySet<Int>()
    private val selectedAlt = ConcurrentHashMap<Int, Int>()

    // IPP interfaces in descriptor order, mapped to the pipes of one simulated IPP-USB device.
    private val ippInterfaces = (ippInterfacesOf(device))
    private val ippDevice: SimulatedIppUsbDevice? =
        if (ipp != null && ippInterfaces.size >= 2) SimulatedIppUsbDevice(ipp, ippInterfaces.size) else null
    private val legacyChannels = ConcurrentHashMap<Int, ByteChannel>()

    fun detach() {
        detached = true
    }

    private fun ifaceAt(number: Int): UsbInterfaceInfo? =
        device.interfaces.firstOrNull { it.interfaceNumber == number && it.alternateSetting == (selectedAlt[number] ?: 0) }

    private fun endpointOwner(address: Int): Pair<UsbInterfaceInfo, Boolean>? {
        val owner = device.interfaces.firstOrNull { i -> i.endpoints.any { it.address == address } } ?: return null
        val iface = ifaceAt(owner.interfaceNumber) ?: return null
        val ep = iface.endpoints.firstOrNull { it.address == address } ?: return null
        return iface to ep.isIn
    }

    override fun claimInterface(iface: UsbInterfaceInfo): Boolean {
        log += "claim ${iface.interfaceNumber}"
        if (detached || claimFailsFor == iface.interfaceNumber) return false
        claimed += iface.interfaceNumber
        return true
    }

    override fun releaseInterface(iface: UsbInterfaceInfo): Boolean {
        log += "release ${iface.interfaceNumber}"
        releases.getOrPut(iface.interfaceNumber) { AtomicInteger() }.incrementAndGet()
        legacyChannels.remove(iface.interfaceNumber)?.close()
        return claimed.remove(iface.interfaceNumber)
    }

    override fun selectAlternateSetting(iface: UsbInterfaceInfo): Boolean {
        log += "alt ${iface.interfaceNumber}/${iface.alternateSetting}"
        if (detached || iface.interfaceNumber !in claimed) return false
        selectedAlt[iface.interfaceNumber] = iface.alternateSetting
        return true
    }

    override fun bulkTransfer(endpointAddress: Int, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        if (detached) return -1
        val (iface, isIn) = endpointOwner(endpointAddress) ?: return -1
        if (iface.interfaceNumber !in claimed) return -1
        return if (isIn) readPacket(iface, buffer, offset, length, timeoutMillis) else write(iface, buffer, offset, length, timeoutMillis)
    }

    private fun write(iface: UsbInterfaceInfo, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        if (stallWrites) {
            Thread.sleep(timeoutMillis.toLong())
            return -1
        }
        return try {
            when (iface.interfaceProtocol) {
                UsbInterfaceInfo.PROTOCOL_IPP_USB -> (ippPipe(iface) ?: return -1).write(buffer, offset, length)
                else -> (legacy(iface) ?: return -1).write(buffer, offset, length)
            }
            length
        } catch (e: java.io.IOException) {
            -1
        }
    }

    private fun readPacket(iface: UsbInterfaceInfo, buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int {
        maxReadRequest.accumulateAndGet(length, ::maxOf)
        val want = minOf(length, packetSize)
        val n: Int = try {
            when (iface.interfaceProtocol) {
                UsbInterfaceInfo.PROTOCOL_IPP_USB -> {
                    val pipe = ippPipe(iface) ?: return -1
                    // A full packet waiting and a buffer smaller than a packet: the transfer overflows.
                    if (pipe.staleBytesForHost >= packetSize && length < packetSize) return -1
                    pipe.read(buffer, offset, want, timeoutMillis)
                }
                else -> (legacy(iface) ?: return -1).read(buffer, offset, want, timeoutMillis)
            }
        } catch (e: java.io.IOException) {
            return -1
        }
        // Zero means nothing arrived: after the whole timeout, the platform reports that as a failure.
        return if (n > 0) n else -1
    }

    private fun ippPipe(iface: UsbInterfaceInfo): SimulatedIppUsbDevice.SimPipe? {
        val index = ippInterfaces.indexOfFirst { it.interfaceNumber == iface.interfaceNumber }
        return ippDevice?.pipes?.getOrNull(index)
    }

    private fun legacy(iface: UsbInterfaceInfo): ByteChannel? {
        if (pjl == null) return null
        return legacyChannels.getOrPut(iface.interfaceNumber) { pjl.open() }
    }

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        offset: Int,
        length: Int,
        timeoutMillis: Int,
    ): Int {
        log += "control 0x${requestType.toString(16)} req $request idx $index"
        if (detached) return -1
        return when {
            requestType == 0xA1 && request == 0 -> deviceId(index, buffer, offset, length)
            request == 2 && requestType in softResetTypes -> {
                softResets.incrementAndGet()
                val iface = device.interfaces.firstOrNull { it.interfaceNumber == index } ?: return -1
                ippPipe(iface)?.recover()
                0
            }
            else -> -1
        }
    }

    private fun deviceId(index: Int, buffer: ByteArray?, offset: Int, length: Int): Int {
        val text = deviceIdText ?: return -1
        val ifaceNumber = index shr 8
        val alt = index and 0xFF
        if (device.interfaces.none { it.interfaceNumber == ifaceNumber && it.alternateSetting == alt }) return -1
        val body = text.toByteArray(Charsets.ISO_8859_1)
        val total = body.size + 2
        val prefix = if (deviceIdLittleEndian) byteArrayOf((total and 0xFF).toByte(), (total shr 8).toByte()) else byteArrayOf((total shr 8).toByte(), (total and 0xFF).toByte())
        val all = prefix + body
        val n = minOf(all.size, length)
        System.arraycopy(all, 0, buffer ?: return -1, offset, n)
        return n
    }

    override fun close() {
        log += "close"
        ippDevice?.close()
        legacyChannels.values.forEach { it.close() }
    }

    /** The plan for the IPP interfaces, so the pipes line up with the descriptor. */
    private fun ippInterfacesOf(device: UsbDeviceInfo): List<UsbInterfaceInfo> =
        (io.github.zsozso01.platen.transport.usb.UsbInterfacePlanner.best(device) as? UsbPrinterPlan.IppUsb)?.interfaces.orEmpty()
}
