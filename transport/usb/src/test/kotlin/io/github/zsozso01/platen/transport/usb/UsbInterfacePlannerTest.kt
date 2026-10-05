package io.github.zsozso01.platen.transport.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Descriptor layouts follow the shapes described in the USB printer class and IPP-USB specifications and in ipp-usb's source. */
class UsbInterfacePlannerTest {
    private val bulkOut = UsbEndpointInfo(0x01, isIn = false, isBulk = true, maxPacketSize = 512)
    private val bulkIn = UsbEndpointInfo(0x81, isIn = true, isBulk = true, maxPacketSize = 512)
    private val interrupt = UsbEndpointInfo(0x83, isIn = true, isBulk = false, maxPacketSize = 8)

    private fun iface(number: Int, alt: Int, cls: Int, sub: Int, proto: Int, vararg eps: UsbEndpointInfo, config: Int = 0) =
        UsbInterfaceInfo(config, number, alt, cls, sub, proto, eps.toList())

    private fun printerIf(number: Int, alt: Int, proto: Int, config: Int = 0) =
        iface(number, alt, 7, 1, proto, bulkOut, *(if (proto == 1) emptyArray() else arrayOf(bulkIn)), config = config)

    private fun device(vendor: Int = 0x1234, vararg interfaces: UsbInterfaceInfo) =
        UsbDeviceInfo(vendor, 0x5678, "Acme", "Printer", null, interfaces.toList())

    @Test
    fun `two protocol-4 interfaces mean IPP over USB`() {
        val plan = UsbInterfacePlanner.best(device(interfaces = arrayOf(printerIf(0, 0, 4), printerIf(1, 0, 4), printerIf(2, 0, 4))))
        val ipp = assertIs<UsbPrinterPlan.IppUsb>(plan)
        assertEquals(listOf(0, 1, 2), ipp.interfaces.map { it.interfaceNumber })
    }

    @Test
    fun `protocol 4 on a non-zero alternate setting is found and alt 0 is not assumed`() {
        // Interface 0: alt 0 legacy bidirectional, alt 1 IPP. Interface 1: only IPP at alt 0. (The layout the spec itself gives.)
        val d = device(interfaces = arrayOf(printerIf(0, 0, 2), printerIf(0, 1, 4), printerIf(1, 0, 4)))
        val plans = UsbInterfacePlanner.plans(d)
        val ipp = assertIs<UsbPrinterPlan.IppUsb>(plans.first())
        assertEquals(listOf(0 to 1, 1 to 0), ipp.interfaces.map { it.interfaceNumber to it.alternateSetting })
        // The legacy interface is still offered as a fallback.
        val legacy = assertIs<UsbPrinterPlan.Legacy>(plans[1])
        assertEquals(0, legacy.iface.interfaceNumber)
        assertEquals(0, legacy.iface.alternateSetting)
        assertTrue(legacy.bidirectional)
    }

    @Test
    fun `a single protocol-4 interface is not enough and falls back to legacy`() {
        val d = device(interfaces = arrayOf(printerIf(0, 0, 2), printerIf(1, 0, 4)))
        val plans = UsbInterfacePlanner.plans(d)
        assertEquals(1, plans.size)
        assertIs<UsbPrinterPlan.Legacy>(plans.single())
    }

    @Test
    fun `the HP non-standard 255 9 1 interfaces count as IPP over USB, but only for HP`() {
        fun hp(n: Int) = iface(n, 0, 0xFF, 9, 1, bulkOut, bulkIn)
        assertIs<UsbPrinterPlan.IppUsb>(UsbInterfacePlanner.best(device(0x03F0, hp(0), hp(1))))
        assertNull(UsbInterfacePlanner.best(device(0x04A9, hp(0), hp(1))), "same descriptors on another vendor are not IPP-USB")
    }

    @Test
    fun `bidirectional is preferred over unidirectional, unidirectional still works`() {
        val both = UsbInterfacePlanner.best(device(interfaces = arrayOf(printerIf(0, 0, 1), printerIf(1, 0, 2))))
        assertEquals(1, assertIs<UsbPrinterPlan.Legacy>(both).iface.interfaceNumber)
        val one = assertIs<UsbPrinterPlan.Legacy>(UsbInterfacePlanner.best(device(interfaces = arrayOf(printerIf(0, 0, 1)))))
        assertFalse(one.bidirectional)
    }

    @Test
    fun `IEEE 1284_4 and vendor-specific interfaces are not used, and a missing endpoint disqualifies an interface`() {
        assertNull(UsbInterfacePlanner.best(device(interfaces = arrayOf(printerIf(0, 0, 3)))))
        assertNull(UsbInterfacePlanner.best(device(interfaces = arrayOf(iface(0, 0, 7, 1, 0xFF, bulkOut, bulkIn)))))
        // protocol 2 without a bulk-in endpoint is unusable
        assertNull(UsbInterfacePlanner.best(device(interfaces = arrayOf(iface(0, 0, 7, 1, 2, bulkOut)))))
        // two "IPP" interfaces without bulk endpoints do not count either
        assertNull(UsbInterfacePlanner.best(device(interfaces = arrayOf(iface(0, 0, 7, 1, 4, interrupt), iface(1, 0, 7, 1, 4, interrupt)))))
    }

    @Test
    fun `the lowest alternate setting that carries IPP is used`() {
        val d = device(interfaces = arrayOf(printerIf(0, 3, 4), printerIf(0, 1, 4), printerIf(1, 2, 4)))
        val ipp = assertIs<UsbPrinterPlan.IppUsb>(UsbInterfacePlanner.best(d))
        assertEquals(listOf(1, 2), ipp.interfaces.map { it.alternateSetting })
    }

    @Test
    fun `at most three IPP interfaces are claimed`() {
        val d = device(interfaces = Array(5) { printerIf(it, 0, 4) })
        assertEquals(3, assertIs<UsbPrinterPlan.IppUsb>(UsbInterfacePlanner.best(d)).interfaces.size)
    }

    @Test
    fun `interfaces from different configurations are never mixed`() {
        // One IPP interface in each of two configurations is not two interfaces of one configuration.
        val d = device(interfaces = arrayOf(printerIf(0, 0, 4, config = 0), printerIf(0, 0, 4, config = 1)))
        assertNull(UsbInterfacePlanner.best(d))
        val ok = device(interfaces = arrayOf(printerIf(0, 0, 4, config = 1), printerIf(1, 0, 4, config = 1)))
        assertEquals(1, UsbInterfacePlanner.best(ok)!!.configurationIndex)
    }

    @Test
    fun `non-printers produce no plan and are not offered`() {
        val keyboard = device(interfaces = arrayOf(iface(0, 0, 3, 1, 1, interrupt)))
        assertNull(UsbInterfacePlanner.best(keyboard))
        assertFalse(keyboard.looksLikePrinter)
        assertTrue(device(interfaces = arrayOf(printerIf(0, 0, 2))).looksLikePrinter)
        assertTrue(device(0x03F0, iface(0, 0, 0xFF, 9, 1, bulkOut, bulkIn)).looksLikePrinter)
        assertEquals("Acme Printer", device(interfaces = emptyArray()).displayName)
        assertEquals("USB printer 1234:5678", UsbDeviceInfo(0x1234, 0x5678, null, null, null, emptyList()).displayName)
    }
}
