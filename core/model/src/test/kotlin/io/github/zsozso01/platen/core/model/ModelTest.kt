package io.github.zsozso01.platen.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {
    @Test
    fun `A4 converts to points and pixels`() {
        assertEquals(595.27, MediaSize.A4.widthPoints, 0.01)
        assertEquals(841.89, MediaSize.A4.heightPoints, 0.01)
        assertEquals(2480, MediaSize.A4.widthPixels(300))
        assertEquals(3508, MediaSize.A4.heightPixels(300))
        assertEquals(1700, MediaSize.LETTER.widthPixels(200))
    }

    @Test
    fun `rotated and samePaperAs ignore orientation and naming`() {
        assertTrue(MediaSize.A4.rotated().isLandscape)
        assertTrue(MediaSize.A4.samePaperAs(MediaSize.A4.rotated()))
        assertTrue(MediaSize.A4.samePaperAs(MediaSize(21010, 29690)))
        assertFalse(MediaSize.A4.samePaperAs(MediaSize.LETTER))
    }

    @Test
    fun `invalid sizes margins and settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { MediaSize(0, 100) }
        assertFailsWith<IllegalArgumentException> { Margins(-1, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { PrintSettings(copies = 0) }
        assertFailsWith<IllegalArgumentException> { PrintSettings(copies = 1000) }
        assertFailsWith<IllegalArgumentException> { PrintSettings(resolutionDpi = 5) }
        assertFailsWith<IllegalArgumentException> { Scaling.Custom(0) }
        assertFailsWith<IllegalArgumentException> { PagesPerSheet(count = 5) }
        assertFailsWith<IllegalArgumentException> { Printer(PrinterId("x"), "x", endpoints = emptyList()) }
    }

    @Test
    fun `page selection resolves ranges parity and clipping`() {
        assertEquals(listOf(1, 2, 3, 4, 5), PageSelection.ALL.resolve(5))
        assertEquals(listOf(2, 3, 5), PageSelection(listOf(2..3, 5..5)).resolve(10))
        assertEquals(listOf(8, 9, 10), PageSelection(listOf(8..99)).resolve(10), "ranges are clipped to the document")
        assertEquals(listOf(1, 3, 5), PageSelection(parity = PageSelection.Parity.ODD_ONLY).resolve(5))
        assertEquals(listOf(2, 4), PageSelection(parity = PageSelection.Parity.EVEN_ONLY).resolve(5))
        assertEquals(listOf(3, 5), PageSelection(listOf(2..5), PageSelection.Parity.ODD_ONLY).resolve(10))
        assertEquals(listOf(2, 1, 2), PageSelection(listOf(2..2, 1..2)).resolve(3), "a page named twice prints twice")
        assertEquals(emptyList(), PageSelection(listOf(20..30)).resolve(10))
    }

    @Test
    fun `page selection parses user text`() {
        assertEquals(PageSelection(listOf(1..3, 5..5, 8..10)), PageSelection.parse("1-3, 5, 8-", 10))
        assertEquals(PageSelection(listOf(1..4)), PageSelection.parse("-4", 10))
        assertEquals(PageSelection.ALL, PageSelection.parse("", 10))
        assertEquals(PageSelection.ALL, PageSelection.parse(" , ", 10))
        listOf("a", "3-1", "0", "1-b", "1--2", "-").forEach { assertNull(PageSelection.parse(it, 10), it) }
    }

    @Test
    fun `capabilities lookups`() {
        val caps = PrinterCapabilities(
            formats = listOf(DocumentFormat("Application/PDF")),
            sides = setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE),
            provenance = mapOf(CapabilityKey.FORMATS to Provenance.REPORTED_IPP),
        )
        assertTrue(caps.supports(DocumentFormat.PDF), "format comparison ignores case")
        assertFalse(caps.supports(DocumentFormat.PWG_RASTER))
        assertTrue(caps.canDuplex)
        assertEquals(Provenance.REPORTED_IPP, caps.provenanceOf(CapabilityKey.FORMATS))
        assertEquals(Provenance.ASSUMED, caps.provenanceOf(CapabilityKey.SIDES))
        assertFalse(PrinterCapabilities().canDuplex)
    }
}
