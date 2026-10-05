package io.github.zsozso01.platen.transport.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsSdTest {
    /** A TXT record shaped like the one a DeskJet 3700 advertises (values abbreviated, serials removed). */
    private val deskjet = DnsSdService(
        instanceName = "HP DeskJet 3700 series [766F51]",
        host = "192.168.1.55",
        port = 631,
        txt = mapOf(
            "txtvers" to "1", "rp" to "ipp/print", "ty" to "HP DeskJet 3700 series", "pdl" to "application/vnd.hp-PCL,image/jpeg,application/PCLm,image/urf,image/pwg-raster",
            "Color" to "T", "Duplex" to "F", "UUID" to "1c852a4d-b800-1f08-abcd-98e7f4766f51", "TLS" to "1.2", "priority" to "20",
        ),
        secure = false,
    )

    @Test
    fun `reads the fields that matter`() {
        val p = DnsSdPrinters.fromService(deskjet)!!
        assertEquals("/ipp/print", p.path)
        assertEquals("HP DeskJet 3700 series [766F51]", p.name)
        assertEquals("HP DeskJet 3700 series", p.makeAndModel)
        assertEquals(true, p.color)
        assertEquals(false, p.duplex)
        assertEquals("1c852a4d-b800-1f08-abcd-98e7f4766f51", p.id)
        assertTrue("image/pwg-raster" in p.formats)
        assertEquals("ipp://192.168.1.55:631/ipp/print", p.address)
        assertEquals(PrinterAddress("192.168.1.55", 631, "/ipp/print", false), PrinterAddress.parse(p.address))
    }

    @Test
    fun `tolerates sloppy records`() {
        val sloppy = DnsSdService("Office", "10.0.0.9", 631, mapOf("RP" to "/printers/office", "UUID" to "urn:uuid:ABC-123", "Color" to "U"), secure = false)
        val p = DnsSdPrinters.fromService(sloppy)!!
        assertEquals("/printers/office", p.path, "leading slash and key case are tolerated")
        assertEquals("abc-123", p.uuid)
        assertNull(p.color, "unknown stays unknown")
        assertNull(p.makeAndModel)
        assertEquals("/ipp/print", DnsSdPrinters.fromService(DnsSdService("x", "h", 1, emptyMap(), false))!!.path, "no rp: the usual path")
    }

    @Test
    fun `unusable services are dropped`() {
        assertNull(DnsSdPrinters.fromService(DnsSdService("x", "h", 0, emptyMap(), false)), "port 0 means not offered")
        assertNull(DnsSdPrinters.fromService(DnsSdService("x", " ", 631, emptyMap(), false)))
    }

    @Test
    fun `an ipv6 host is bracketed in the address`() {
        val p = DnsSdPrinters.fromService(DnsSdService("v6", "fe80::1", 631, mapOf("rp" to "ipp/print"), false))!!
        assertEquals("ipp://[fe80::1]:631/ipp/print", p.address)
        assertEquals("fe80::1", PrinterAddress.parse(p.address)!!.host)
    }

    @Test
    fun `the same printer seen twice becomes one entry and prefers plain ipp`() {
        val plain = DnsSdPrinters.fromService(deskjet)!!
        val tls = DnsSdPrinters.fromService(DnsSdService(deskjet.instanceName, deskjet.host, 443, deskjet.txt, secure = true))!!
        val other = DnsSdPrinters.fromService(DnsSdService("Another", "192.168.1.60", 631, mapOf("rp" to "ipp/print"), false))!!
        val merged = DnsSdPrinters.merge(listOf(tls, other, plain))
        assertEquals(2, merged.size)
        assertEquals(listOf("Another", "HP DeskJet 3700 series [766F51]"), merged.map { it.name })
        assertEquals(false, merged.last().secure)
    }

    @Test
    fun `printers without a uuid are told apart by address`() {
        val a = DnsSdPrinters.fromService(DnsSdService("A", "10.0.0.1", 631, mapOf("rp" to "ipp"), false))!!
        val b = DnsSdPrinters.fromService(DnsSdService("B", "10.0.0.2", 631, mapOf("rp" to "ipp"), false))!!
        assertEquals(2, DnsSdPrinters.merge(listOf(a, b)).size)
        assertEquals(1, DnsSdPrinters.merge(listOf(a, a.copy(name = "A again"))).size)
    }
}
