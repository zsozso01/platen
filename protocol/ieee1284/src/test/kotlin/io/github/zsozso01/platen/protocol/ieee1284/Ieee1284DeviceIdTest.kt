package io.github.zsozso01.platen.protocol.ieee1284

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** All device IDs in this file are synthetic: they follow the real format but are not dumps of any device. */
class Ieee1284DeviceIdTest {
    private val laser =
        "MFG:Acme;MDL:LaserWriter 9000;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF,URF;CLS:PRINTER;DES:Acme LaserWriter 9000;SN:ABC123;"

    @Test
    fun `parses core fields from text`() {
        val id = Ieee1284DeviceId.parse(laser)
        assertEquals("Acme", id.manufacturer)
        assertEquals("LaserWriter 9000", id.model)
        assertEquals("PRINTER", id.deviceClass)
        assertEquals("ABC123", id.serialNumber)
        assertEquals("Acme LaserWriter 9000", id.displayName)
        assertEquals(listOf("PJL", "PCL", "PCLXL", "POSTSCRIPT", "PDF", "URF"), id.commandSets)
    }

    @Test
    fun `maps command sets to known languages`() {
        val id = Ieee1284DeviceId.parse(laser)
        assertEquals(
            setOf(
                PrinterLanguage.PJL,
                PrinterLanguage.PCL5,
                PrinterLanguage.PCLXL,
                PrinterLanguage.POSTSCRIPT,
                PrinterLanguage.PDF,
                PrinterLanguage.URF,
            ),
            id.languages,
        )
    }

    @Test
    fun `accepts long key names and mixed case`() {
        val id = Ieee1284DeviceId.parse("manufacturer:Acme;Model:Inkjet 1;Command Set:PCL3GUI,PCLm;Class:PRINTER;")
        assertEquals("Acme", id.manufacturer)
        assertEquals("Inkjet 1", id.model)
        assertEquals(setOf(PrinterLanguage.PCL3GUI, PrinterLanguage.PCLM), id.languages)
    }

    @Test
    fun `unknown command sets are kept as raw strings but not as languages`() {
        val id = Ieee1284DeviceId.parse("MFG:Acme;MDL:X;CMD:BDC,FOO,PJL;")
        assertEquals(listOf("BDC", "FOO", "PJL"), id.commandSets)
        assertEquals(setOf(PrinterLanguage.PJL), id.languages)
    }

    @Test
    fun `repeated keys are joined`() {
        val id = Ieee1284DeviceId.parse("MFG:A;CMD:PJL;CMD:PDF;")
        assertEquals(listOf("PJL", "PDF"), id.commandSets)
    }

    @Test
    fun `values may contain colons`() {
        val id = Ieee1284DeviceId.parse("MFG:A;DES:Acme: the printer;")
        assertEquals("Acme: the printer", id.description)
    }

    @Test
    fun `malformed input never throws`() {
        listOf("", ";;;", "garbage", ":::", "MFG", "\u0000\u0000", "MFG:;MDL:;").forEach {
            val id = Ieee1284DeviceId.parse(it)
            assertNull(id.model?.takeIf(String::isNotBlank))
        }
    }

    @Test
    fun `display name falls back to manufacturer and model`() {
        assertEquals("Acme X1", Ieee1284DeviceId.parse("MFG:Acme;MDL:X1;").displayName)
        assertNull(Ieee1284DeviceId.parse("CLS:PRINTER;").displayName)
    }

    // --- byte form (USB GET_DEVICE_ID) --------------------------------------------------------

    private fun withBigEndianPrefix(text: String, adjust: Int = 0): ByteArray {
        val body = text.toByteArray(Charsets.ISO_8859_1)
        val len = body.size + 2 + adjust
        return byteArrayOf((len shr 8).toByte(), len.toByte()) + body
    }

    private fun withLittleEndianPrefix(text: String): ByteArray {
        val body = text.toByteArray(Charsets.ISO_8859_1)
        val len = body.size + 2
        return byteArrayOf(len.toByte(), (len shr 8).toByte()) + body
    }

    @Test
    fun `bytes with spec-conformant big-endian prefix`() {
        val id = Ieee1284DeviceId.parse(withBigEndianPrefix(laser))
        assertEquals("Acme", id.manufacturer)
        assertEquals("LaserWriter 9000", id.model)
    }

    @Test
    fun `bytes with little-endian prefix`() {
        val id = Ieee1284DeviceId.parse(withLittleEndianPrefix(laser))
        assertEquals("Acme", id.manufacturer)
        assertEquals("LaserWriter 9000", id.model)
    }

    @Test
    fun `bytes with wrong length value still parse`() {
        val id = Ieee1284DeviceId.parse(withBigEndianPrefix(laser, adjust = -20))
        assertEquals("LaserWriter 9000", id.model)
        val id2 = Ieee1284DeviceId.parse(withBigEndianPrefix(laser, adjust = +30))
        assertEquals("LaserWriter 9000", id2.model)
    }

    @Test
    fun `bytes without any prefix`() {
        val id = Ieee1284DeviceId.parse(laser.toByteArray(Charsets.ISO_8859_1))
        assertEquals("Acme", id.manufacturer)
        assertEquals("LaserWriter 9000", id.model)
    }

    @Test
    fun `short length prefix such as 0x00 0x80 is not mistaken for text`() {
        // 0x00 is not printable, so it must be treated as a prefix byte.
        val text = "MFG:A;MDL:B;"
        val body = text.toByteArray()
        val bytes = byteArrayOf(0x00, (body.size + 2).toByte()) + body
        assertEquals("B", Ieee1284DeviceId.parse(bytes).model)
        assertTrue(Ieee1284DeviceId.parse(bytes).raw.startsWith("MFG:"))
    }

    @Test
    fun `empty bytes give an empty id`() {
        assertEquals(emptyMap(), Ieee1284DeviceId.parse(ByteArray(0)).fields)
    }
}
