package io.github.zsozso01.platen.platform.render

import android.graphics.Color
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.layout.LayoutPlanner
import io.github.zsozso01.platen.core.layout.PageGeometry
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.PagesPerSheet
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Scaling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AndroidSideRasterizerTest {
    private val opened = mutableListOf<RenderableDocument>()
    private val rasterizers = mutableListOf<AndroidSideRasterizer>()

    @After
    fun cleanUp() {
        rasterizers.forEach { it.close() }
        opened.forEach { it.close() }
    }

    private fun open(file: File): RenderableDocument = PdfRendererDocument.open(file, file.name).also { opened += it }

    private fun geometry(doc: RenderableDocument) = List(doc.pageCount) { doc.pageGeometry(it) }

    /** A 50 dpi A4 face is 413 x 585 pixels: small enough to inspect pixel by pixel. */
    private fun render(doc: RenderableDocument, settings: PrintSettings, sideIndex: Int = 0, unprintable: Margins = Margins.NONE, format: RasterPixelFormat = RasterPixelFormat.RGB24): Pair<Rendered, Int> {
        val plan = LayoutPlanner.plan(geometry(doc), settings, MediaSize.A4, unprintable)
        val rasterizer = AndroidSideRasterizer(doc, bandRows = 64).also { rasterizers += it }
        val side = rasterizer.rasterize(plan.sides[sideIndex], MediaSize.A4, 50, format)
        val bytes = ByteArray(side.widthPx * side.heightPx * format.bytesPerPixel)
        side.readRows(0, side.heightPx, bytes)
        return Rendered(side.widthPx, side.heightPx, format.bytesPerPixel, bytes) to plan.sides.size
    }

    private class Rendered(val w: Int, val h: Int, val bpp: Int, val bytes: ByteArray) {
        fun pixel(x: Int, y: Int): Int {
            val o = (y * w + x) * bpp
            return if (bpp == 1) Color.rgb(bytes[o].toInt() and 0xFF, bytes[o].toInt() and 0xFF, bytes[o].toInt() and 0xFF)
            else Color.rgb(bytes[o].toInt() and 0xFF, bytes[o + 1].toInt() and 0xFF, bytes[o + 2].toInt() and 0xFF)
        }
        fun isRed(x: Int, y: Int) = Color.red(pixel(x, y)) > 200 && Color.green(pixel(x, y)) < 60 && Color.blue(pixel(x, y)) < 60
        fun isBlue(x: Int, y: Int) = Color.blue(pixel(x, y)) > 200 && Color.red(pixel(x, y)) < 60 && Color.green(pixel(x, y)) < 60
        fun isWhite(x: Int, y: Int) = pixel(x, y) == Color.WHITE
    }

    private val noMargins = PrintSettings(margins = MarginSetting.None, scaling = Scaling.FitToPage)

    @Test
    fun documentReportsPageSizesInPoints() {
        val doc = open(TestDocuments.pdf("sizes.pdf", listOf(TestDocuments.A4, TestDocuments.A4_LANDSCAPE)))
        assertEquals(2, doc.pageCount)
        assertEquals(PageGeometry(595.0, 842.0), doc.pageGeometry(0))
        assertEquals(PageGeometry(842.0, 595.0), doc.pageGeometry(1))
        assertTrue(doc.pdf != null && doc.pdf!!.length > 0)
    }

    @Test
    fun anUnscaledPageLandsAtTheOrigin() {
        val doc = open(TestDocuments.pdf("plain.pdf", listOf(TestDocuments.A4)))
        val (img, _) = render(doc, noMargins)
        assertEquals(413, img.w)
        assertEquals(585, img.h)
        // The 40 x 40 pt marker is 28 x 28 px at 50 dpi, at the top-left.
        assertTrue("marker at top-left", img.isRed(10, 10))
        assertTrue("page background white", img.isWhite(200, 400) || Color.red(img.pixel(200, 400)) > 200)
        assertTrue("nothing at the top-right", !img.isRed(img.w - 10, 10))
    }

    @Test
    fun aLandscapePageIsTurnedOntoAPortraitSheet() {
        val doc = open(TestDocuments.pdf("land.pdf", listOf(TestDocuments.A4_LANDSCAPE), listOf(Color.BLUE)))
        val (img, _) = render(doc, noMargins)
        // Turned a quarter turn clockwise: the page's top-left marker ends up in the sheet's top-right corner.
        assertTrue("blue marker top-right", img.isBlue(img.w - 10, 10))
        assertTrue("not top-left", !img.isBlue(10, 10))
    }

    @Test
    fun twoPagesShareASheetSideBySide() {
        val doc = open(TestDocuments.pdf("two.pdf", listOf(TestDocuments.A4, TestDocuments.A4), listOf(Color.RED, Color.BLUE)))
        val (img, sides) = render(doc, noMargins.copy(pagesPerSheet = PagesPerSheet(2)))
        assertEquals(1, sides)
        // Landscape canvas on a portrait sheet: page 1's marker top-right, page 2's marker half way down on the right edge.
        assertTrue("page 1 marker", img.isRed(img.w - 10, 10))
        assertTrue("page 2 marker", img.isBlue(img.w - 10, img.h / 2 + 12))
    }

    @Test
    fun marginsKeepContentOutOfTheUnprintableBorder() {
        val doc = open(TestDocuments.pdf("margin.pdf", listOf(TestDocuments.A4)))
        val unprintable = Margins.uniform(2540) // one inch all round
        val (img, _) = render(doc, PrintSettings(scaling = Scaling.FitToPage), unprintable = unprintable)
        assertTrue("the border band stays white", img.isWhite(2, 2) && img.isWhite(img.w / 2, 5))
        // 1 inch border = 50 px. Fit-to-page scales to 75.8% and centres vertically, so the 21 px marker
        // sits at x 50..71 and y 71..92.
        assertTrue("the marker moved inward", img.isRed(60, 80))
        assertTrue("and not into the border", !img.isRed(30, 30) && !img.isRed(60, 40))
    }

    @Test
    fun greyscaleRenderingOfAColourPageStaysBelowWhiteAtTheMarker() {
        val doc = open(TestDocuments.pdf("gray.pdf", listOf(TestDocuments.A4)))
        val (img, _) = render(doc, noMargins, format = RasterPixelFormat.GRAY8)
        val v = Color.red(img.pixel(10, 10))
        assertTrue("red luma is mid-dark, was $v", v in 40..140)
        assertEquals(255, Color.red(img.pixel(img.w - 5, img.h - 5)))
    }

    @Test
    fun bandsAreSeamlessAcrossBoundaries() {
        // A page with a solid marker larger than a band must render identically whatever the band size.
        val doc = open(TestDocuments.pdf("bands.pdf", listOf(TestDocuments.A4)))
        val plan = LayoutPlanner.plan(geometry(doc), noMargins, MediaSize.A4)
        fun render(band: Int): ByteArray {
            val r = AndroidSideRasterizer(doc, bandRows = band).also { rasterizers += it }
            val side = r.rasterize(plan.sides[0], MediaSize.A4, 50, RasterPixelFormat.RGB24)
            return ByteArray(side.widthPx * side.heightPx * 3).also { side.readRows(0, side.heightPx, it) }
        }
        val small = render(7)
        val large = render(585)
        var differing = 0
        for (i in small.indices) if (small[i] != large[i]) differing++
        // Anti-aliased text edges can differ by a few pixels between bands; the page as a whole must agree.
        assertTrue("$differing of ${small.size} bytes differ", differing < small.size / 500)
    }
}
