package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.engine.Decision.Outcome
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PagesPerSheet
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrintPlannerTest {
    private fun outcome(plan: PrintPlan, kind: SettingKind) = plan.decisions.lastOrNull { it.setting == kind }?.outcome

    // ---------------------------------------------------------------- raster-only inkjet (DeskJet-like)

    @Test
    fun `a raster-only inkjet gets PWG raster at its only resolution`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(3), PrintSettings(), inkjetCaps)
        assertEquals(RouteKind.RASTER, plan.route)
        assertEquals(DocumentFormat.PWG_RASTER, plan.format)
        val raster = assertNotNull(plan.raster)
        assertEquals(300, raster.dpi)
        assertEquals("srgb_8", raster.pwgType)
        assertEquals(RasterPixelFormat.RGB24, raster.pixelFormat)
        assertEquals(SheetBack.ROTATED, raster.sheetBack)
        assertEquals(1, plan.passes.size)
        assertEquals(3, plan.passes.single().faces.size)
        assertEquals(Sides.ONE_SIDED, plan.printer.sides)
        assertEquals(Orientation.PORTRAIT, plan.printer.orientation)
        assertEquals(Scaling.ActualSize, plan.printer.scaling, "already scaled by Platen")
        assertFalse(plan.needsReload)
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString())
    }

    @Test
    fun `layout respects the printer's unprintable margins`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(scaling = Scaling.FitToPage), inkjetCaps)
        val bottom = MediaSize.A4.heightPoints - 1270 * 72.0 / 2540
        assertTrue(plan.layout.printableArea.bottom <= bottom + 0.01)
    }

    @Test
    fun `monochrome request uses the grey pixel format`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(color = ColorMode.MONOCHROME), inkjetCaps)
        assertEquals("sgray_8", plan.raster!!.pwgType)
        assertEquals(RasterPixelFormat.GRAY8, plan.raster.pixelFormat)
        assertEquals(ColorMode.MONOCHROME, plan.printer.colorMode)
    }

    @Test
    fun `asking for colour from a black and white printer is approximated and said so`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(color = ColorMode.COLOR), laserCaps)
        assertEquals(Outcome.APPROXIMATED, outcome(plan, SettingKind.COLOR))
        assertEquals("sgray_8", plan.raster!!.pwgType)
    }

    @Test
    fun `duplex on a simplex inkjet becomes manual duplex with two passes`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(5), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), inkjetCaps)
        assertEquals(Sides.ONE_SIDED, plan.printer.sides)
        assertTrue(plan.needsReload)
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, plan.manualDuplex!!.binding)
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.SIDES))
        val (fronts, backs) = plan.passes
        assertEquals(Pass.Role.FRONTS, fronts.role)
        assertEquals(Pass.Role.BACKS, backs.role)
        // 5 pages: sheets are (1,2) (3,4) (5,blank)
        assertEquals(listOf(0, 2, 4), fronts.faces.map { it.sideIndex })
        assertEquals(listOf(1, 3, null), backs.faces.map { it.sideIndex })
        assertTrue(backs.faces.last().isBlank)
    }

    @Test
    fun `page selection, reverse and booklet are done by the app on a raster route`() {
        val plan = PrintPlanner.plan(
            SyntheticDocument.a4Pages(10),
            PrintSettings(pages = PageSelection(listOf(2..6)), reverseOrder = true),
            inkjetCaps,
        )
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.PAGES))
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.REVERSE))
        assertEquals(listOf(6, 5, 4, 3, 2), plan.layout.sides.map { it.placements.single().sourcePage })
        val booklet = PrintPlanner.plan(SyntheticDocument.a4Pages(8), PrintSettings(booklet = true), inkjetCaps)
        assertEquals(Outcome.BY_APP, outcome(booklet, SettingKind.BOOKLET))
        assertEquals(Sides.TWO_SIDED_SHORT_EDGE, booklet.manualDuplex!!.binding, "a booklet on a simplex printer is a manual short-edge job")
        assertEquals(2, booklet.passes.size)
        assertEquals(2, booklet.passes[0].faces.size) // 8 pages -> 4 sides -> 2 sheets
    }

    @Test
    fun `collated copies are sent by the app so they come out in order`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(2), PrintSettings(copies = 3, collate = true), inkjetCaps)
        assertEquals(1, plan.printer.copies)
        assertEquals(6, plan.passes.single().faces.size)
        assertEquals(listOf(0, 1, 0, 1, 0, 1), plan.passes.single().faces.map { it.sideIndex })
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.COPIES))
    }

    @Test
    fun `uncollated copies use the printer's copies`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(2), PrintSettings(copies = 3, collate = false), inkjetCaps)
        assertEquals(3, plan.printer.copies)
        assertEquals(2, plan.passes.single().faces.size)
        assertEquals(Outcome.BY_PRINTER, outcome(plan, SettingKind.COPIES))
    }

    @Test
    fun `more copies than the printer supports are sent by the app even if uncollated`() {
        val caps = inkjetCaps.copy(maxCopies = 2)
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(copies = 5, collate = false), caps)
        assertEquals(5, plan.passes.single().faces.size)
        assertEquals(1, plan.printer.copies)
    }

    @Test
    fun `app copies with manual duplex keep every copy sheet-aligned`() {
        // 3 pages per copy -> padded to 4 per copy so the copy boundary falls on a sheet boundary
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(3), PrintSettings(copies = 2, sides = Sides.TWO_SIDED_LONG_EDGE), inkjetCaps)
        val (fronts, backs) = plan.passes
        assertEquals(listOf(0, 2, 0, 2), fronts.faces.map { it.sideIndex })
        assertEquals(listOf(1, null, 1, null), backs.faces.map { it.sideIndex })
    }

    @Test
    fun `resolution follows quality within what the printer offers`() {
        val twoRes = laserCaps.copy(formats = listOf(DocumentFormat.PWG_RASTER))
        fun dpi(settings: PrintSettings) = PrintPlanner.plan(SyntheticDocument.a4Pages(1, asPdf = false), settings, twoRes).raster!!.dpi
        assertEquals(300, dpi(PrintSettings()))
        assertEquals(300, dpi(PrintSettings(quality = Quality.DRAFT)))
        assertEquals(600, dpi(PrintSettings(quality = Quality.HIGH)))
        assertEquals(600, dpi(PrintSettings(resolutionDpi = 1200)))
        val approx = PrintPlanner.plan(SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(resolutionDpi = 1200), twoRes)
        assertEquals(Outcome.APPROXIMATED, outcome(approx, SettingKind.RESOLUTION))
    }

    @Test
    fun `printer duplex on a raster printer sets duplex and tumble in the header parameters`() {
        val caps = laserCaps.copy(formats = listOf(DocumentFormat.PWG_RASTER))
        val long = PrintPlanner.plan(SyntheticDocument.a4Pages(3, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), caps)
        assertTrue(long.raster!!.printerDuplex)
        assertFalse(long.raster.tumble)
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, long.printer.sides)
        assertEquals(1, long.passes.size)
        assertEquals(listOf(0, 1, 2, null), long.passes.single().faces.map { it.sideIndex }, "padded so the last sheet pairs")
        val short = PrintPlanner.plan(SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_SHORT_EDGE), caps)
        assertTrue(short.raster!!.tumble)
    }

    // ---------------------------------------------------------------- PDF-capable laser

    @Test
    fun `a PDF laser prints the original PDF with only the printer's own settings`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(copies = 2, sides = Sides.TWO_SIDED_LONG_EDGE), laserCaps)
        assertEquals(RouteKind.PASS_THROUGH, plan.route)
        assertEquals(DocumentFormat.PDF, plan.format)
        assertNull(plan.raster)
        assertTrue(plan.passes.isEmpty())
        assertEquals(2, plan.printer.copies)
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, plan.printer.sides)
        assertEquals(Outcome.BY_PRINTER, outcome(plan, SettingKind.SIDES))
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString())
    }

    @Test
    fun `page ranges, number-up and scaling are delegated to a capable printer`() {
        val plan = PrintPlanner.plan(
            SyntheticDocument.a4Pages(10),
            PrintSettings(pages = PageSelection(listOf(2..4, 7..9)), pagesPerSheet = PagesPerSheet(4), scaling = Scaling.FitToPage),
            laserCaps,
        )
        assertEquals(RouteKind.PASS_THROUGH, plan.route)
        assertEquals(listOf(2..4, 7..9), plan.printer.pageRanges)
        assertEquals(4, plan.printer.numberUp)
        assertEquals(Scaling.FitToPage, plan.printer.scaling)
    }

    @Test
    fun `settings a printer cannot express move a PDF job to the raster route`() {
        for (settings in listOf(
            PrintSettings(reverseOrder = true),
            PrintSettings(pages = PageSelection(parity = PageSelection.Parity.ODD_ONLY)),
            PrintSettings(pages = PageSelection(listOf(5..6, 1..2))), // not ascending
            PrintSettings(scaling = Scaling.Custom(80)),
            PrintSettings(orientation = Orientation.LANDSCAPE),
            PrintSettings(margins = MarginSetting.Custom(Margins.uniform(2000))),
            PrintSettings(pagesPerSheet = PagesPerSheet(2), orientation = Orientation.LANDSCAPE),
            PrintSettings(booklet = true), // laserCaps has no booklet maker
        )) {
            val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(8), settings, laserCaps)
            assertEquals(RouteKind.RASTER, plan.route, "$settings")
            assertEquals(DocumentFormat.PWG_RASTER, plan.format)
            assertEquals(600, PrintPlanner.plan(SyntheticDocument.a4Pages(8), settings.copy(quality = Quality.HIGH), laserCaps).raster!!.dpi)
        }
    }

    @Test
    fun `a printer with a booklet maker takes booklets natively`() {
        val caps = laserCaps.copy(bookletMaker = true)
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(8), PrintSettings(booklet = true, sides = Sides.TWO_SIDED_SHORT_EDGE), caps)
        assertEquals(RouteKind.PASS_THROUGH, plan.route)
        assertTrue(plan.printer.bookletMaker)
    }

    @Test
    fun `a PDF-only printer degrades with warnings instead of failing`() {
        val pdfOnly = laserCaps.copy(formats = listOf(DocumentFormat.PDF))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(reverseOrder = true), pdfOnly)
        assertEquals(RouteKind.PASS_THROUGH, plan.route)
        assertEquals(Outcome.IGNORED, outcome(plan, SettingKind.REVERSE))
        assertTrue(plan.warnings.isNotEmpty())
    }

    @Test
    fun `duplex the printer lacks cannot be done for a PDF-only printer and is reported`() {
        val pdfOnlySimplex = laserCaps.copy(formats = listOf(DocumentFormat.PDF), sides = setOf(Sides.ONE_SIDED))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), pdfOnlySimplex)
        assertEquals(Sides.ONE_SIDED, plan.printer.sides)
        assertEquals(Outcome.IGNORED, outcome(plan, SettingKind.SIDES))
    }

    // ---------------------------------------------------------------- shared behaviour

    @Test
    fun `trays, paper types and bins are only requested if the printer lists them`() {
        val plan = PrintPlanner.plan(
            SyntheticDocument.a4Pages(1),
            PrintSettings(tray = MediaSource("tray-2"), outputBin = "face-down"),
            laserCaps,
        )
        assertEquals(MediaSource("tray-2"), plan.printer.tray)
        assertEquals("face-down", plan.printer.outputBin)
        val ignored = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(tray = MediaSource("tray-9"), outputBin = "mailbox-1"), laserCaps)
        assertNull(ignored.printer.tray)
        assertNull(ignored.printer.outputBin)
        assertEquals(Outcome.IGNORED, outcome(ignored, SettingKind.TRAY))
        assertEquals(Outcome.IGNORED, outcome(ignored, SettingKind.OUTPUT_BIN))
    }

    @Test
    fun `economy maps to draft quality where available and is reported otherwise`() {
        val ok = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(economy = true), laserCaps)
        assertEquals(Quality.DRAFT, ok.printer.quality)
        assertEquals(Outcome.APPROXIMATED, outcome(ok, SettingKind.ECONOMY))
        val none = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(economy = true), laserCaps.copy(qualities = emptySet()))
        assertNull(none.printer.quality)
        assertEquals(Outcome.IGNORED, outcome(none, SettingKind.ECONOMY))
    }

    @Test
    fun `paper size is matched against what the printer lists`() {
        val listed = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(paper = MediaSize.LETTER), inkjetCaps)
        assertEquals(MediaSize.LETTER.pwgName, listed.printer.paper!!.pwgName)
        assertEquals(Outcome.BY_PRINTER, outcome(listed, SettingKind.PAPER))
        val unlisted = PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(paper = MediaSize.A3), inkjetCaps)
        assertEquals(Outcome.APPROXIMATED, outcome(unlisted, SettingKind.PAPER))
    }

    @Test
    fun `default paper comes from the printer, else the first listed, else A4`() {
        assertEquals(MediaSize.A4, PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), inkjetCaps).layout.sheet)
        val letterDefault = inkjetCaps.copy(defaultMedia = MediaSize.LETTER)
        assertEquals(MediaSize.LETTER, PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), letterDefault).layout.sheet)
        val noDefault = inkjetCaps.copy(defaultMedia = null, mediaSizes = listOf(MediaSize.LEGAL))
        assertEquals(MediaSize.LEGAL, PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), noDefault).layout.sheet)
        val nothing = inkjetCaps.copy(defaultMedia = null, mediaSizes = emptyList())
        assertEquals(MediaSize.A4, PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), nothing).layout.sheet)
    }

    @Test
    fun `unknown capabilities are handled conservatively`() {
        val bare = PrinterCapabilities(formats = listOf(DocumentFormat.PWG_RASTER))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(copies = 2), bare)
        assertEquals(RouteKind.RASTER, plan.route)
        assertEquals(300, plan.raster!!.dpi)
        assertEquals("srgb_8", plan.raster.pwgType)
        assertEquals(1, plan.printer.copies, "unknown copy support: the app sends the pages")
        assertEquals(4, plan.passes.single().faces.size)
    }

    @Test
    fun `planning failures are explained`() {
        assertFailsWith<PlanningException> { PrintPlanner.plan(SyntheticDocument(emptyList()), PrintSettings(), inkjetCaps) }
        val none = assertFailsWith<PlanningException> {
            PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(pages = PageSelection(listOf(9..12))), inkjetCaps)
        }
        assertTrue("selected" in none.message!!)
        val noFormats = assertFailsWith<PlanningException> {
            PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), PrinterCapabilities(formats = listOf(DocumentFormat.PCLM)))
        }
        assertTrue("application/PCLm" in noFormats.message!!)
        val odd = inkjetCaps.copy(raster = inkjetCaps.raster!!.copy(colorTypes = listOf("adobe-rgb_8")))
        assertFailsWith<PlanningException> { PrintPlanner.plan(SyntheticDocument.a4Pages(1), PrintSettings(), odd) }
    }

    @Test
    fun `a PDF the printer can read is not sent when the document is not a PDF`() {
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(), laserCaps)
        assertEquals(RouteKind.RASTER, plan.route)
    }
}
