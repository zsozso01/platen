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
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import io.github.zsozso01.platen.testing.support.inkjetCaps
import io.github.zsozso01.platen.testing.support.laserCaps
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
    fun `a PDF-only printer gets layout features by receiving a PDF made of page images`() {
        val pdfOnly = laserCaps.copy(formats = listOf(DocumentFormat.PDF), resolutionsDpi = listOf(300, 600, 1200))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(reverseOrder = true, quality = Quality.HIGH), pdfOnly)
        assertEquals(RouteKind.RASTER, plan.route)
        assertEquals(DocumentFormat.PDF, plan.format)
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.REVERSE))
        assertEquals(600, plan.raster?.dpi, "high quality stops at 600 dpi in a PDF, not the printer's 1200")
        assertEquals(SheetBack.NORMAL, plan.raster?.sheetBack, "a PDF page is always upright")
        assertTrue(plan.warnings.isEmpty())
    }

    @Test
    fun `a PDF-only printer with PWG raster too gets PWG for layout work`() {
        val both = laserCaps.copy(formats = listOf(DocumentFormat.PDF, DocumentFormat.PWG_RASTER))
        assertEquals(DocumentFormat.PWG_RASTER, PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(reverseOrder = true), both).format)
    }

    @Test
    fun `images can be printed on a PDF-only printer`() {
        val pdfOnly = laserCaps.copy(formats = listOf(DocumentFormat.PDF))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(), pdfOnly)
        assertEquals(RouteKind.RASTER, plan.route)
        assertEquals(DocumentFormat.PDF, plan.format)
    }

    @Test
    fun `duplex the PDF-only printer lacks is done by the app in two passes`() {
        val pdfOnlySimplex = laserCaps.copy(formats = listOf(DocumentFormat.PDF), sides = setOf(Sides.ONE_SIDED))
        val plan = PrintPlanner.plan(SyntheticDocument.a4Pages(4), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), pdfOnlySimplex)
        assertEquals(Sides.ONE_SIDED, plan.printer.sides)
        assertEquals(Outcome.BY_APP, outcome(plan, SettingKind.SIDES))
        assertEquals(2, plan.passes.size)
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

    // ---------------------------------------------------------------- reverse order with two-sided printing

    private val longEdge = PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE)
    private val longEdgeReversed = longEdge.copy(reverseOrder = true)
    private val backsLastToFirst = ManualDuplexOptions(reverseBackOrder = true)

    private fun planFor(pages: Int, settings: PrintSettings, caps: PrinterCapabilities = inkjetCaps) =
        PrintPlanner.plan(SyntheticDocument.a4Pages(pages), settings, caps)

    /** The pages printed on each face of a pass, in the order given; null for a face with nothing on it. */
    private fun PrintPlan.labels(faces: List<Face>): List<String?> = faces.map { layout.pagesLabel(it.sideIndex) }

    @Test
    fun `reversing an odd manual duplex job sends the lone page first with a blank back`() {
        val plan = planFor(9, longEdgeReversed)
        val (fronts, backs) = plan.passes
        assertEquals(listOf(0, 2, 4, 6, 8), fronts.faces.map { it.sideIndex })
        assertEquals(listOf("9", "7", "5", "3", "1"), plan.labels(fronts.faces))
        assertEquals(listOf(1, 3, 5, 7, 9), backs.faces.map { it.sideIndex })
        assertEquals(listOf(null, "8", "6", "4", "2"), plan.labels(backs.faces))
        assertTrue(backs.faces.none { it.isBlank }, "the blank back is a side of the layout, so it is not padding")
        assertEquals(listOf("2", "4", "6", "8", null), plan.labels(backs.inSendOrder(backsLastToFirst)))
        assertTrue(plan.printsBothSides)
        assertEquals(5, plan.sheets().size)
    }

    @Test
    fun `reversing an even manual duplex job needs no blank`() {
        val plan = planFor(8, longEdgeReversed)
        val (fronts, backs) = plan.passes
        assertEquals(listOf("7", "5", "3", "1"), plan.labels(fronts.faces))
        assertEquals(listOf("8", "6", "4", "2"), plan.labels(backs.faces))
    }

    @Test
    fun `only the back pass is sent last to first`() {
        val plan = planFor(4, longEdge)
        val (fronts, backs) = plan.passes
        assertEquals(fronts.faces, fronts.inSendOrder(backsLastToFirst))
        assertEquals(backs.faces, backs.inSendOrder(ManualDuplexOptions()))
        assertEquals(backs.faces.reversed(), backs.inSendOrder(backsLastToFirst))
        val single = Pass(listOf(Face(0), Face(1)))
        assertEquals(single.faces, single.inSendOrder(backsLastToFirst), "a pass that is not a back pass is left alone")
    }

    @Test
    fun `a printer that duplexes itself gets the blank back inside its single pass`() {
        val plan = planFor(3, longEdgeReversed, laserCaps)
        assertEquals(RouteKind.RASTER, plan.route, "reverse order is done by the app")
        assertTrue(plan.raster!!.printerDuplex)
        val pass = plan.passes.single()
        assertEquals(listOf(0, 1, 2, 3), pass.faces.map { it.sideIndex })
        assertEquals(listOf("3", null, "1", "2"), plan.labels(pass.faces))
        assertTrue(plan.printsBothSides)
        assertNull(plan.reloadGuide())
    }

    @Test
    fun `app copies with reverse and manual duplex repeat the same sheets`() {
        val plan = planFor(3, longEdgeReversed.copy(copies = 2, collate = true))
        val (fronts, backs) = plan.passes
        assertEquals(listOf(0, 2, 0, 2), fronts.faces.map { it.sideIndex })
        assertEquals(listOf(1, 3, 1, 3), backs.faces.map { it.sideIndex })
        assertEquals(listOf("3", "1", "3", "1"), plan.labels(fronts.faces))
        assertEquals(listOf(null, "2", null, "2"), plan.labels(backs.faces))
    }

    @Test
    fun `a blank side reaches the raster writers as no side at all`() {
        val plan = planFor(3, longEdgeReversed)
        val backs = plan.passes[1]
        assertEquals(listOf(false, true), RasterFaces.build(plan, backs).map { it.side != null })
        assertEquals(listOf(true, false), RasterFaces.build(plan, backs, backsLastToFirst).map { it.side != null })
        val duplexPlan = planFor(3, longEdgeReversed, laserCaps)
        val duplexFaces = RasterFaces.build(duplexPlan, duplexPlan.passes.single())
        assertEquals(listOf(true, false, true, true), duplexFaces.map { it.side != null })
    }

    @Test
    fun `printsBothSides covers the printer's duplexer and manual duplex but not one-sided jobs`() {
        assertFalse(planFor(3, PrintSettings()).printsBothSides)
        assertTrue(planFor(3, longEdge).printsBothSides, "manual duplex")
        val passThrough = planFor(3, longEdge, laserCaps)
        assertEquals(RouteKind.PASS_THROUGH, passThrough.route)
        assertTrue(passThrough.printsBothSides, "the printer's own duplexer")
        assertEquals(2, passThrough.sheets().size)
        assertEquals(3, planFor(3, PrintSettings()).sheets().size)
    }

    // ---------------------------------------------------------------- the reload guide

    private fun reloadGuide(pages: Int, settings: PrintSettings, options: ManualDuplexOptions = ManualDuplexOptions()) =
        assertNotNull(planFor(pages, settings).reloadGuide(options))

    @Test
    fun `an odd page count leaves the last sheet with a blank back`() {
        val guide = reloadGuide(9, longEdge)
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, guide.binding)
        assertEquals(0, guide.firstFront)
        assertEquals(5, guide.sheetCount)
        assertEquals(
            listOf(
                ReloadSheet(1, 0, 8), ReloadSheet(3, 2, 6), ReloadSheet(5, 4, 4),
                ReloadSheet(7, 6, 2), ReloadSheet(null, 8, 0),
            ),
            guide.sheets,
        )
        assertEquals(ReloadSheet(1, 0, 8), guide.firstSheet)
        assertEquals(ReloadSheet(1, 0, 8), guide.firstPrinted)
        assertEquals(ReloadSheet(1, 0, 8), guide.mixUpExample)
        assertEquals(listOf(8), guide.blankBackFronts)
    }

    @Test
    fun `starting the backs from the other end of the pile reverses the sheets and moves the blank first`() {
        val guide = reloadGuide(9, longEdge, backsLastToFirst)
        assertEquals(0, guide.firstFront)
        assertEquals(
            listOf(
                ReloadSheet(null, 8, 0), ReloadSheet(7, 6, 2), ReloadSheet(5, 4, 4),
                ReloadSheet(3, 2, 6), ReloadSheet(1, 0, 8),
            ),
            guide.sheets,
        )
        assertEquals(ReloadSheet(null, 8, 0), guide.firstSheet)
        assertEquals(ReloadSheet(7, 6, 2), guide.firstPrinted)
        assertEquals(listOf(8), guide.blankBackFronts)
    }

    @Test
    fun `reversing an odd job puts the blank back on the first sheet`() {
        val guide = reloadGuide(9, longEdgeReversed)
        assertEquals(0, guide.firstFront)
        assertEquals(
            listOf(
                ReloadSheet(null, 0, 8), ReloadSheet(3, 2, 6), ReloadSheet(5, 4, 4),
                ReloadSheet(7, 6, 2), ReloadSheet(9, 8, 0),
            ),
            guide.sheets,
        )
        assertEquals(ReloadSheet(3, 2, 6), guide.firstPrinted)
        assertEquals(ReloadSheet(3, 2, 6), guide.mixUpExample)
        assertEquals(listOf(0), guide.blankBackFronts)
    }

    @Test
    fun `a sheet that lands behind the same front from either end is no mix-up example`() {
        val guide = reloadGuide(5, longEdgeReversed)
        assertEquals(listOf(ReloadSheet(null, 0, 4), ReloadSheet(3, 2, 2), ReloadSheet(5, 4, 0)), guide.sheets)
        assertEquals(ReloadSheet(3, 2, 2), guide.firstPrinted, "the middle sheet is the first with a printed back")
        assertEquals(ReloadSheet(5, 4, 0), guide.mixUpExample, "only an outer sheet shows what a mix-up looks like")
    }

    @Test
    fun `every copy has its own blank back`() {
        val guide = reloadGuide(3, longEdge.copy(copies = 2, collate = true))
        assertEquals(
            listOf(ReloadSheet(1, 0, 2), ReloadSheet(null, 2, 0), ReloadSheet(1, 0, 2), ReloadSheet(null, 2, 0)),
            guide.sheets,
        )
        assertEquals(listOf(2), guide.blankBackFronts)
    }

    @Test
    fun `a single page prints nothing on the back`() {
        val guide = reloadGuide(1, longEdge)
        assertEquals(listOf(ReloadSheet(null, 0, 0)), guide.sheets)
        assertNull(guide.firstPrinted)
        assertNull(guide.mixUpExample)
        assertEquals(listOf(0), guide.blankBackFronts)
    }

    @Test
    fun `short edge and booklets carry their binding into the guide`() {
        val shortEdge = PrintSettings(sides = Sides.TWO_SIDED_SHORT_EDGE)
        assertEquals(Sides.TWO_SIDED_SHORT_EDGE, reloadGuide(4, shortEdge).binding)
        val booklet = reloadGuide(8, PrintSettings(booklet = true))
        assertEquals(Sides.TWO_SIDED_SHORT_EDGE, booklet.binding)
        assertEquals(listOf(ReloadSheet(1, 0, 2), ReloadSheet(3, 2, 0)), booklet.sheets)
    }

    @Test
    fun `there is no guide unless the user turns the paper over by hand`() {
        assertNull(planFor(3, PrintSettings()).reloadGuide(), "one-sided")
        assertNull(planFor(3, longEdge, laserCaps).reloadGuide(), "pass-through duplex")
        assertNull(planFor(3, longEdgeReversed, laserCaps).reloadGuide(), "printer duplex")
    }
}
