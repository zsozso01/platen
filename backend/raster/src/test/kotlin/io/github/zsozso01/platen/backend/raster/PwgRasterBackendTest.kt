package io.github.zsozso01.platen.backend.raster

import io.github.zsozso01.platen.core.engine.BackendJob
import io.github.zsozso01.platen.core.engine.ManualDuplexOptions
import io.github.zsozso01.platen.core.engine.Pass
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.PrintPlanner
import io.github.zsozso01.platen.core.engine.RasterFaces
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.RasterProfile
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.raster.PwgPageHeader
import io.github.zsozso01.platen.protocol.raster.PwgRasterReader
import io.github.zsozso01.platen.testing.support.SolidBlockRasterizer
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import io.github.zsozso01.platen.testing.support.inkjetCaps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PwgRasterBackendTest {
    /** A low resolution keeps pages small enough that pixel-exact assertions run in milliseconds. */
    private val lowRes = inkjetCaps.copy(
        resolutionsDpi = listOf(50),
        raster = RasterProfile(colorTypes = listOf("sgray_8", "srgb_8"), resolutionsDpi = listOf(50), sheetBack = "rotated"),
        unprintableMargins = null,
    )

    private class Decoded(val header: PwgPageHeader, val rows: List<ByteArray>) {
        val width = header.widthPixels
        val height = header.heightPixels
        private val bpp = header.colorType.bitsPerPixel / 8
        fun gray(x: Int, y: Int): Int = rows[y][x * bpp].toInt() and 0xFF
    }

    private fun write(plan: PrintPlan, document: SyntheticDocument, pass: Pass = plan.passes.first(), options: ManualDuplexOptions = ManualDuplexOptions(), events: MutableList<JobEvent> = mutableListOf()): List<Decoded> {
        val out = ByteArrayOutputStream()
        val faces = RasterFaces.build(plan, pass, options)
        PwgRasterBackend(bandRows = 7).write(BackendJob(plan, document, SolidBlockRasterizer(), faces), out, events::add)
        val reader = PwgRasterReader(ByteArrayInputStream(out.toByteArray()))
        val pages = mutableListOf<Decoded>()
        while (true) {
            val header = reader.nextPage() ?: break
            pages += Decoded(header, List(header.heightPixels) { reader.readLine()!! })
        }
        return pages
    }

    private fun plan(pages: Int, settings: PrintSettings = PrintSettings(), caps: PrinterCapabilities = lowRes) =
        PrintPlanner.plan(SyntheticDocument.a4Pages(pages, asPdf = false), settings, caps) to SyntheticDocument.a4Pages(pages, asPdf = false)

    @Test
    fun `pages are written in order with correct headers`() {
        val (plan, doc) = plan(3, PrintSettings(color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME))
        val events = mutableListOf<JobEvent>()
        val pages = write(plan, doc, events = events)
        assertEquals(3, pages.size)
        // A4 at 50 dpi is 413 x 585 pixels
        pages.forEach {
            assertEquals(413, it.width)
            assertEquals(585, it.height)
            assertEquals(50, it.header.resolutionDpiX)
            assertEquals("iso_a4_210x297mm", it.header.pageSizeName)
            assertEquals(595 to 842, it.header.pageSizePoints)
            assertEquals(3, it.header.totalPageCount)
            assertEquals(0, it.header.numCopies, "copies travel in the IPP job, not the page header")
            assertEquals(false, it.header.duplex)
        }
        // The shade encodes the page number, read at the centre of each page.
        assertEquals(listOf(SolidBlockRasterizer.shade(1), SolidBlockRasterizer.shade(2), SolidBlockRasterizer.shade(3)), pages.map { it.gray(200, 300) })
        assertEquals<List<JobEvent>>(listOf(JobEvent.Preparing(1, 3), JobEvent.Preparing(2, 3), JobEvent.Preparing(3, 3)), events)
    }

    @Test
    fun `a rendered page of a selection and reverse comes out in that order`() {
        val (plan, doc) = plan(8, PrintSettings(pages = PageSelection(listOf(2..4)), reverseOrder = true, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME))
        assertEquals(listOf(4, 3, 2).map(SolidBlockRasterizer::shade), write(plan, doc).map { it.gray(200, 300) })
    }

    @Test
    fun `colour jobs write RGB pixels`() {
        val (plan, doc) = plan(1, PrintSettings(color = io.github.zsozso01.platen.core.model.ColorMode.COLOR))
        val page = write(plan, doc).single()
        assertEquals("srgb_8", page.header.colorType.keyword)
        assertEquals(413 * 3, page.rows[0].size)
    }

    @Test
    fun `the page content lands where the layout said, in sheet coordinates`() {
        // A landscape page on a portrait sheet is turned a quarter turn clockwise: the page's marked top-left corner
        // ends up in the sheet's top-right corner.
        val landscape = SyntheticDocument(listOf(io.github.zsozso01.platen.core.layout.PageGeometry(842.0, 595.0)), pdf = null)
        val p = PrintPlanner.plan(landscape, PrintSettings(margins = io.github.zsozso01.platen.core.model.MarginSetting.None, scaling = io.github.zsozso01.platen.core.model.Scaling.FitToPage, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), lowRes)
        val page = write(p, landscape).single()
        val shade = SolidBlockRasterizer.shade(1)
        assertEquals(SolidBlockRasterizer.MARKER, page.gray(page.width - 3, 3), "marker is in the top-right corner")
        assertEquals(shade, page.gray(3, 3), "the opposite corner is plain page")
        assertEquals(shade, page.gray(page.width - 3, page.height - 3))
    }

    @Test
    fun `blank faces are white`() {
        val (plan, doc) = plan(1, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), lowRes.copy(sides = setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE)))
        val pages = write(plan, doc)
        assertEquals(2, pages.size, "one page is padded to a full sheet")
        assertTrue(pages[1].rows.all { row -> row.all { it == 0xFF.toByte() } })
    }

    // --- duplex ---------------------------------------------------------------------------------

    private val duplexCaps = lowRes.copy(sides = setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE, Sides.TWO_SIDED_SHORT_EDGE))

    private fun duplexCaps(back: String) = duplexCaps.copy(raster = lowRes.raster!!.copy(sheetBack = back))

    @Test
    fun `printer duplex sets the header flags and leaves normal back sides untouched`() {
        val (plan, doc) = plan(2, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), duplexCaps("normal"))
        val (front, back) = write(plan, doc)
        assertEquals(true, front.header.duplex)
        assertEquals(false, front.header.tumble)
        assertEquals(1 to 1, back.header.crossFeedTransform to back.header.feedTransform)
        assertEquals(SolidBlockRasterizer.MARKER, back.gray(3, 3), "marker still top-left")
    }

    @Test
    fun `a rotated back side is turned half a turn and says so in its header`() {
        val (plan, doc) = plan(2, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, margins = io.github.zsozso01.platen.core.model.MarginSetting.None, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), duplexCaps("rotated"))
        val (front, back) = write(plan, doc)
        assertEquals(1 to 1, front.header.crossFeedTransform to front.header.feedTransform)
        assertEquals(-1 to -1, back.header.crossFeedTransform to back.header.feedTransform)
        assertEquals(SolidBlockRasterizer.MARKER, front.gray(3, 3))
        assertEquals(SolidBlockRasterizer.MARKER, back.gray(back.width - 3, back.height - 3), "the marker is now bottom-right")
    }

    @Test
    fun `a flipped back side on long edge is mirrored top to bottom`() {
        val (plan, doc) = plan(2, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, margins = io.github.zsozso01.platen.core.model.MarginSetting.None, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), duplexCaps("flipped"))
        val back = write(plan, doc)[1]
        assertEquals(1 to -1, back.header.crossFeedTransform to back.header.feedTransform)
        assertEquals(SolidBlockRasterizer.MARKER, back.gray(3, back.height - 3), "marker mirrored to the bottom-left")
    }

    @Test
    fun `short edge tumble is flagged and table 9 is honoured`() {
        val (plan, doc) = plan(2, PrintSettings(sides = Sides.TWO_SIDED_SHORT_EDGE, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME), duplexCaps("flipped"))
        val (front, back) = write(plan, doc)
        assertEquals(true, front.header.duplex)
        assertEquals(true, front.header.tumble)
        assertEquals(-1 to 1, back.header.crossFeedTransform to back.header.feedTransform)
    }

    // --- manual duplex --------------------------------------------------------------------------

    @Test
    fun `manual duplex prints fronts, then backs last to first`() {
        val (plan, doc) = plan(4, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME))
        val (frontsPass, backsPass) = plan.passes
        val fronts = write(plan, doc, frontsPass)
        val backs = write(plan, doc, backsPass)
        assertEquals(listOf(1, 3).map(SolidBlockRasterizer::shade), fronts.map { it.gray(200, 300) })
        assertEquals(listOf(4, 2).map(SolidBlockRasterizer::shade), backs.map { it.gray(200, 300) })
        assertEquals(false, fronts[0].header.duplex, "each manual pass is one-sided")
        val inOrder = write(plan, doc, backsPass, ManualDuplexOptions(reverseBackOrder = false))
        assertEquals(listOf(2, 4).map(SolidBlockRasterizer::shade), inOrder.map { it.gray(200, 300) })
    }

    @Test
    fun `manual short edge backs are turned half a turn, and the toggle undoes it`() {
        val settings = PrintSettings(sides = Sides.TWO_SIDED_SHORT_EDGE, margins = io.github.zsozso01.platen.core.model.MarginSetting.None, color = io.github.zsozso01.platen.core.model.ColorMode.MONOCHROME)
        val (plan, doc) = plan(2, settings)
        val backsPass = plan.passes[1]
        val turned = write(plan, doc, backsPass).single()
        assertEquals(SolidBlockRasterizer.MARKER, turned.gray(turned.width - 3, turned.height - 3))
        val toggled = write(plan, doc, backsPass, ManualDuplexOptions(rotateBacks = true)).single()
        assertEquals(SolidBlockRasterizer.MARKER, toggled.gray(3, 3))
    }

    @Test
    fun `a pass-through plan cannot be written by the raster backend`() {
        val pdfDoc = SyntheticDocument.a4Pages(1)
        val pdfPlan = PrintPlanner.plan(pdfDoc, PrintSettings(), io.github.zsozso01.platen.testing.support.laserCaps)
        assertEquals(DocumentFormat.PDF, pdfPlan.format)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            PwgRasterBackend().write(BackendJob(pdfPlan, pdfDoc, SolidBlockRasterizer()), ByteArrayOutputStream()) {}
        }
    }
}
