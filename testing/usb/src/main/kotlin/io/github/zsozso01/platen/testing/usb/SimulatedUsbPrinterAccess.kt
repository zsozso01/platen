package io.github.zsozso01.platen.testing.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import io.github.zsozso01.platen.transport.usb.UsbEndpointInfo
import io.github.zsozso01.platen.transport.usb.UsbInterfaceInfo
import io.github.zsozso01.platen.transport.usb.UsbIppPipe
import io.github.zsozso01.platen.transport.usb.UsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.UsbPrinterPlan
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A simulated attached printer: IPP-over-USB interfaces backed by a [FakeIppPrinter], a classic interface
 * backed by a [FakePjlPrinter], or both, as a real device can offer.
 */
class SimulatedUsbPrinterAccess(
    override val device: UsbDeviceInfo,
    private val ippPrinter: FakeIppPrinter? = null,
    private val pjlPrinter: FakePjlPrinter? = null,
    private val deviceIdText: String? = null,
) : UsbPrinterAccess {
    /** Makes claiming the IPP interfaces fail, as when another app or driver holds them. */
    @Volatile var ippClaimFails = false

    val ippClaims = AtomicInteger()
    val legacyClaims = AtomicInteger()
    val ippDevices = CopyOnWriteArrayList<SimulatedIppUsbDevice>()

    @Volatile var closed = false
        private set

    override fun openIppPipes(plan: UsbPrinterPlan.IppUsb): List<UsbIppPipe> {
        ippClaims.incrementAndGet()
        if (ippClaimFails || ippPrinter == null) throw IOException("Could not claim the IPP interface")
        // Claiming again gives the host fresh pipes to a device that kept its state; here, a new set to the same printer.
        return SimulatedIppUsbDevice(ippPrinter, plan.interfaces.size).also { ippDevices += it }.pipes
    }

    override fun openLegacy(plan: UsbPrinterPlan.Legacy): ByteChannel {
        legacyClaims.incrementAndGet()
        val printer = pjlPrinter ?: throw IOException("Could not claim the printer interface")
        return printer.open()
    }

    override fun readDeviceId(iface: UsbInterfaceInfo): String? = deviceIdText

    override fun close() {
        closed = true
        ippDevices.forEach(SimulatedIppUsbDevice::close)
    }

    companion object {
        /** Each interface has its own endpoint addresses, as on real devices: 0x01/0x81 for interface 0, 0x02/0x82 for 1, ... */
        private fun printerIf(number: Int, alt: Int, protocol: Int): UsbInterfaceInfo {
            val out = UsbEndpointInfo(0x01 + number, isIn = false, isBulk = true, maxPacketSize = 512)
            val input = UsbEndpointInfo(0x81 + number, isIn = true, isBulk = true, maxPacketSize = 512)
            return UsbInterfaceInfo(0, number, alt, 7, 1, protocol, if (protocol == 1) listOf(out) else listOf(out, input))
        }

        /**
         * Descriptors laid out the way the IPP-USB specification gives its own example: interface 0 offers the
         * classic bidirectional protocol at alternate 0 and IPP at alternate 1; interface 1 offers IPP only.
         */
        fun hpLaserDescriptors(ippUsb: Boolean = true, legacy: Boolean = true): UsbDeviceInfo {
            val interfaces = buildList {
                if (legacy) add(printerIf(0, 0, UsbInterfaceInfo.PROTOCOL_BIDIRECTIONAL))
                if (ippUsb) {
                    add(printerIf(0, 1, UsbInterfaceInfo.PROTOCOL_IPP_USB))
                    add(printerIf(1, 0, UsbInterfaceInfo.PROTOCOL_IPP_USB))
                }
            }
            return UsbDeviceInfo(0x03F0, 0xD72A, "HP", "LaserJet MFP E42540", null, interfaces)
        }
    }
}
