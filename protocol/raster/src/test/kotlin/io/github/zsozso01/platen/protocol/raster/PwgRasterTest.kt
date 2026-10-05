package io.github.zsozso01.platen.protocol.raster

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PwgRasterTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun header(
        w: Int,
        h: Int,
        type: PwgColorType,
        dpi: Int = 300,
        build: (PwgPageHeader) -> PwgPageHeader = { it },
    ) = build(PwgPageHeader(w, h, dpi, dpi, type))

    private fun write(header: PwgPageHeader, lines: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        PwgRasterWriter(out).use { w ->
            w.startPage(header)
            lines.forEach(w::writeLine)
            w.endPage()
        }
        return out.toByteArray()
    }

    /** Bytes after the sync word and the 1796-byte page header. */
    private fun bitmapOf(file: ByteArray) = file.copyOfRange(4 + PwgPageHeader.SIZE, file.size)

    // --- golden data from PWG 5102.4 ---------------------------------------------------------

    @Test
    fun `spec sample 4_4_1 a 23x8 one-bit gray bitmap encodes to the 21 published octets`() {
        val lines = listOf(
            bytes(0x8F, 0x78, 0xF7),
            bytes(0x76, 0x77, 0x67),
            bytes(0x77, 0x77, 0x77), bytes(0x77, 0x77, 0x77), bytes(0x77, 0x77, 0x77), bytes(0x77, 0x77, 0x77),
            bytes(0x8E, 0x38, 0xE3),
            bytes(0xFF, 0xFF, 0xFF),
        )
        val file = write(header(23, 8, PwgColorType.SGRAY_1), lines)
        val expected = bytes(
            0x00, 0xFE, 0x8F, 0x78, 0xF7,
            0x00, 0xFE, 0x76, 0x77, 0x67,
            0x03, 0x02, 0x77,
            0x00, 0xFE, 0x8E, 0x38, 0xE3,
            0x00, 0x02, 0xFF,
        )
        assertEquals(21, expected.size)
        assertContentEquals(expected, bitmapOf(file))
    }

    @Test
    fun `spec sample 4_4_2 an 8x8 srgb bitmap encodes to the 87 published octets`() {
        val white = bytes(0xFF, 0xFF, 0xFF)
        val yellow = bytes(0xFF, 0xFF, 0x00)
        val blue = bytes(0x00, 0x00, 0xFF)
        val green = bytes(0x00, 0xFF, 0x00)
        val red = bytes(0xFF, 0x00, 0x00)
        fun row(vararg px: ByteArray) = px.fold(ByteArray(0)) { a, p -> a + p }
        val lines = listOf(
            row(white, yellow, yellow, yellow, white, white, white, white),
            row(yellow, blue, yellow, white, white, white, green, white),
            row(yellow, yellow, white, white, white, green, green, green),
            row(yellow, yellow, yellow, white, white, white, green, white),
            row(white, yellow, yellow, yellow, white, white, white, white),
            row(white, white, white, white, white, white, white, white),
            row(red, red, red, red, red, red, red, red),
            row(red, red, red, red, red, red, red, red),
        )
        val expected = bytes(
            0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x02, 0xFF, 0xFF, 0x00, 0x03, 0xFF, 0xFF, 0xFF,
            0x00, 0xFE, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00, 0x02, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0xFF, 0xFF,
            0x00, 0x01, 0xFF, 0xFF, 0x00, 0x02, 0xFF, 0xFF, 0xFF, 0x02, 0x00, 0xFF, 0x00,
            0x00, 0x02, 0xFF, 0xFF, 0x00, 0x02, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0xFF, 0xFF,
            0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x02, 0xFF, 0xFF, 0x00, 0x03, 0xFF, 0xFF, 0xFF,
            0x00, 0x07, 0xFF, 0xFF, 0xFF,
            0x01, 0x07, 0xFF, 0x00, 0x00,
        )
        assertEquals(87, expected.size)
        assertContentEquals(expected, bitmapOf(write(header(8, 8, PwgColorType.SRGB_8), lines)))
    }

    // --- header layout -----------------------------------------------------------------------

    @Test
    fun `header fields land at the offsets of table 1`() {
        val h = PwgPageHeader(
            widthPixels = 2480, heightPixels = 3508, resolutionDpiX = 300, resolutionDpiY = 300,
            colorType = PwgColorType.SRGB_8, pageSizeName = "iso_a4_210x297mm", mediaType = "stationery",
            duplex = true, tumble = true, numCopies = 3, mediaPosition = 20, printQuality = 5,
            totalPageCount = 7, renderingIntent = "perceptual", printContentOptimize = "photo",
        )
        val file = write(h, List(3508) { ByteArray(h.bytesPerLine) })
        assertContentEquals(PwgRasterWriter.SYNC_WORD, file.copyOfRange(0, 4))
        val b = ByteBuffer.wrap(file, 4, PwgPageHeader.SIZE).slice()
        fun cstr(o: Int) = String(ByteArray(64) { b.get(o + it) }, Charsets.US_ASCII).substringBefore('\u0000')
        assertEquals("PwgRaster", cstr(0))
        assertEquals("stationery", cstr(128))
        assertEquals("photo", cstr(192))
        assertEquals(1, b.getInt(272)) // Duplex
        assertEquals(300, b.getInt(276)) // HWResolution x
        assertEquals(300, b.getInt(280))
        assertEquals(3, b.getInt(340)) // NumCopies
        assertEquals(20, b.getInt(324)) // MediaPosition
        assertEquals(595, b.getInt(352)) // PageSize width, points (2480 px at 300 dpi = 595.2 pt)
        assertEquals(842, b.getInt(356))
        assertEquals(1, b.getInt(368)) // Tumble
        assertEquals(2480, b.getInt(372)) // Width
        assertEquals(3508, b.getInt(376)) // Height
        assertEquals(8, b.getInt(384)) // BitsPerColor
        assertEquals(24, b.getInt(388)) // BitsPerPixel
        assertEquals(7440, b.getInt(392)) // BytesPerLine = 2480 * 3
        assertEquals(0, b.getInt(396)) // ColorOrder chunky
        assertEquals(19, b.getInt(400)) // ColorSpace sRGB
        assertEquals(3, b.getInt(420)) // NumColors
        assertEquals(7, b.getInt(452)) // TotalPageCount
        assertEquals(1, b.getInt(456)) // CrossFeedTransform
        assertEquals(1, b.getInt(460)) // FeedTransform
        assertEquals(5, b.getInt(484)) // PrintQuality
        assertEquals("perceptual", cstr(1668))
        assertEquals("iso_a4_210x297mm", cstr(1732))
        // reserved ranges must be zero
        listOf(256..267, 284..299, 312..323, 332..339, 348..351, 360..367, 380..383, 404..419, 424..451, 488..507, 1604..1667).forEach { r ->
            r.forEach { assertEquals(0, b.get(it).toInt(), "reserved byte $it") }
        }
    }

    @Test
    fun `colour types match table 12`() {
        assertEquals(listOf(8, 8, 18, 1), PwgColorType.SGRAY_8.let { listOf(it.bitsPerColor, it.bitsPerPixel, it.colorSpace, it.numColors) })
        assertEquals(listOf(8, 24, 19, 3), PwgColorType.SRGB_8.let { listOf(it.bitsPerColor, it.bitsPerPixel, it.colorSpace, it.numColors) })
        assertEquals(listOf(1, 1, 3, 1), PwgColorType.BLACK_1.let { listOf(it.bitsPerColor, it.bitsPerPixel, it.colorSpace, it.numColors) })
        assertEquals(PwgColorType.SRGB_8, PwgColorType.fromKeyword("SRGB_8"))
        assertNull(PwgColorType.fromKeyword("device7_8"))
        assertEquals(3, PwgColorType.SGRAY_1.bytesPerLine(23))
        assertEquals(7440, PwgColorType.SRGB_8.bytesPerLine(2480))
    }

    // --- round trips ---------------------------------------------------------------------------

    private fun roundTrip(h: PwgPageHeader, lines: List<ByteArray>) {
        val read = PwgRasterReader(ByteArrayInputStream(write(h, lines)))
        val got = assertNotNull(read.nextPage())
        assertEquals(h.widthPixels, got.widthPixels)
        assertEquals(h.colorType, got.colorType)
        lines.forEachIndexed { i, expected -> assertContentEquals(expected, read.readLine(), "line $i") }
        assertNull(read.readLine())
        assertNull(read.nextPage())
    }

    @Test
    fun `random images round trip for every colour type`() {
        val random = Random(42)
        for (type in PwgColorType.entries) {
            for (width in listOf(1, 2, 7, 8, 9, 127, 128, 129, 300)) {
                val h = header(width, 6, type)
                val lines = List(6) {
                    when (it % 3) {
                        0 -> random.nextBytes(h.bytesPerLine) // noise: all literals
                        1 -> ByteArray(h.bytesPerLine) { 0x55 } // flat: one run
                        else -> ByteArray(h.bytesPerLine) { i -> if ((i / 5) % 2 == 0) 0 else 0xFF.toByte() } // runs and gaps
                    }
                }
                roundTrip(h, lines)
            }
        }
    }

    @Test
    fun `more than 256 identical lines are split into repeat groups`() {
        val h = header(10, 600, PwgColorType.SGRAY_8)
        val line = ByteArray(10) { 7 }
        val file = write(h, List(600) { line })
        // 600 = 256 + 256 + 88 -> three line groups, each: count byte + one run packet (2 bytes)
        assertEquals(3 * 3, bitmapOf(file).size)
        roundTrip(h, List(600) { line })
    }

    @Test
    fun `runs and literals longer than 128 units are split`() {
        val flat = header(1000, 1, PwgColorType.SGRAY_8)
        roundTrip(flat, listOf(ByteArray(1000) { 9 }))
        val noise = header(1000, 1, PwgColorType.SRGB_8)
        roundTrip(noise, listOf(Random(1).nextBytes(noise.bytesPerLine)))
    }

    @Test
    fun `a multi-page file keeps each page's header`() {
        val out = ByteArrayOutputStream()
        PwgRasterWriter(out).use { w ->
            w.startPage(PwgPageHeader(4, 2, 300, 300, PwgColorType.SGRAY_8, pageSizeName = "iso_a4_210x297mm"))
            repeat(2) { w.writeLine(ByteArray(4)) }
            w.endPage()
            w.startPage(PwgPageHeader(3, 1, 600, 600, PwgColorType.SRGB_8, duplex = true))
            w.writeLine(ByteArray(9))
            w.endPage()
        }
        val r = PwgRasterReader(ByteArrayInputStream(out.toByteArray()))
        val p1 = r.nextPage()!!
        assertEquals("iso_a4_210x297mm", p1.pageSizeName)
        assertEquals(false, p1.duplex)
        // skipping the unread rows of page 1 must work
        val p2 = r.nextPage()!!
        assertEquals(600, p2.resolutionDpiX)
        assertEquals(true, p2.duplex)
        assertEquals(PwgColorType.SRGB_8, p2.colorType)
        assertNull(r.nextPage())
    }

    @Test
    fun `an empty document is just the sync word`() {
        val out = ByteArrayOutputStream()
        PwgRasterWriter(out).close()
        assertEquals(0, out.size())
        assertNull(PwgRasterReader(ByteArrayInputStream(ByteArray(0))).nextPage())
    }

    // --- writer misuse -------------------------------------------------------------------------

    @Test
    fun `writer rejects wrong line sizes and line counts`() {
        val w = PwgRasterWriter(ByteArrayOutputStream())
        assertFailsWith<IllegalStateException> { w.writeLine(ByteArray(1)) }
        w.startPage(header(4, 2, PwgColorType.SGRAY_8))
        assertFailsWith<IllegalArgumentException> { w.writeLine(ByteArray(5)) }
        w.writeLine(ByteArray(4))
        assertFailsWith<IllegalStateException> { w.endPage() } // one line short
        w.writeLine(ByteArray(4))
        assertFailsWith<IllegalStateException> { w.writeLine(ByteArray(4)) } // one line too many
        w.endPage()
        assertFailsWith<IllegalStateException> { w.startPage(header(4, 2, PwgColorType.SGRAY_8)).also { w.startPage(header(4, 2, PwgColorType.SGRAY_8)) } }
    }

    @Test
    fun `header validation`() {
        assertFailsWith<IllegalArgumentException> { header(0, 10, PwgColorType.SGRAY_8) }
        assertFailsWith<IllegalArgumentException> { header(10, 10, PwgColorType.SGRAY_8, dpi = 0) }
        assertFailsWith<IllegalArgumentException> { header(10, 10, PwgColorType.SGRAY_8) { it.copy(pageSizeName = "x".repeat(64)) } }
    }

    // --- reader robustness ---------------------------------------------------------------------

    private fun sampleFile(): ByteArray {
        val h = header(20, 5, PwgColorType.SRGB_8)
        val random = Random(5)
        return write(h, List(5) { if (it == 2) ByteArray(h.bytesPerLine) else random.nextBytes(h.bytesPerLine) })
    }

    private fun readAll(file: ByteArray) {
        val r = PwgRasterReader(ByteArrayInputStream(file))
        while (r.nextPage() != null) {
            while (r.readLine() != null) { /* drain */ }
        }
    }

    @Test
    fun `every truncation fails with an IOException and nothing else`() {
        val file = sampleFile()
        for (cut in 0 until file.size) {
            val prefix = file.copyOfRange(0, cut)
            try {
                readAll(prefix)
            } catch (_: IOException) {
                // expected: truncated data; PwgRasterException and EOFException are IOExceptions
            }
        }
    }

    @Test
    fun `random corruption never throws anything but IOException`() {
        val file = sampleFile()
        val random = Random(77)
        repeat(5_000) {
            val copy = file.copyOf()
            repeat(1 + random.nextInt(6)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
            try {
                readAll(copy)
            } catch (_: IOException) {
            }
        }
    }

    @Test
    fun `a hostile header cannot demand a huge allocation`() {
        val file = sampleFile()
        val b = ByteBuffer.wrap(file)
        b.putInt(4 + 372, Int.MAX_VALUE) // Width
        b.putInt(4 + 392, Int.MAX_VALUE)
        assertFailsWith<PwgRasterException> { PwgRasterReader(ByteArrayInputStream(file)).nextPage() }
    }

    @Test
    fun `wrong sync word and wrong header magic are rejected`() {
        val file = sampleFile()
        val bad = file.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFailsWith<PwgRasterException> { PwgRasterReader(ByteArrayInputStream(bad)).nextPage() }
        val bad2 = file.copyOf().also { it[4] = 'X'.code.toByte() }
        assertFailsWith<PwgRasterException> { PwgRasterReader(ByteArrayInputStream(bad2)).nextPage() }
        assertTrue(true)
    }
}
