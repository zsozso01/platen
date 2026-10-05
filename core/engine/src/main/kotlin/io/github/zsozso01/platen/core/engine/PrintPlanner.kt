package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.engine.Decision.Outcome
import io.github.zsozso01.platen.core.layout.LayoutPlan
import io.github.zsozso01.platen.core.layout.LayoutPlanner
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.PrinterScaling
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides
import kotlin.math.abs

/**
 * Turns what the user asked for ([PrintSettings]) and what the printer reported
 * ([PrinterCapabilities]) into a concrete [PrintPlan]: which format to send, which settings the
 * printer carries out, which Platen carries out itself, and which cannot be done at all.
 *
 * The planner never consults a printer database. If the printer did not report something, it is
 * handled conservatively and the decision says so.
 */
public object PrintPlanner {
    private const val DEFAULT_RASTER_DPI = 300

    public fun plan(
        document: DocumentSource,
        settings: PrintSettings,
        caps: PrinterCapabilities,
        jobName: String = document.name,
    ): PrintPlan {
        if (document.pageCount <= 0) throw PlanningException("The document has no pages")
        val pages = List(document.pageCount, document::pageGeometry)
        val decisions = mutableListOf<Decision>()
        fun decide(kind: SettingKind, outcome: Outcome, detail: String? = null) {
            decisions += Decision(kind, outcome, detail)
        }

        val sheet = chooseSheet(settings, caps, ::decide)
        val layout = LayoutPlanner.plan(pages, settings, sheet, caps.unprintableMargins ?: Margins.NONE)
        if (layout.isEmpty) throw PlanningException("No pages are selected")

        val wantedSides = layout.requiredSides ?: settings.sides
        val pdfAvailable = document.pdf != null && caps.supports(DocumentFormat.PDF)
        val nativeOk = pdfAvailable && printerCanLayOutNatively(settings, caps, wantedSides)
        val rasterAvailable = rasterFormat(caps) != null

        return when {
            nativeOk -> passThrough(settings, caps, jobName, sheet, layout, wantedSides, degraded = false, decisions)
            rasterAvailable -> raster(settings, caps, jobName, sheet, layout, wantedSides, decisions)
            pdfAvailable -> passThrough(settings, caps, jobName, sheet, layout, wantedSides, degraded = true, decisions)
            else -> throw PlanningException(
                "The printer accepts none of the formats Platen can produce " +
                    "(printer reports: ${caps.formats.joinToString { it.mime }.ifEmpty { "nothing" }})",
            )
        }
    }

    // --- paper ----------------------------------------------------------------------------------

    private fun chooseSheet(settings: PrintSettings, caps: PrinterCapabilities, decide: (SettingKind, Outcome, String?) -> Unit): MediaSize {
        val listed = caps.mediaSizes
        settings.paper?.let { wanted ->
            val match = listed.firstOrNull { it.samePaperAs(wanted) }
            if (match != null) {
                decide(SettingKind.PAPER, Outcome.BY_PRINTER, null)
                return match
            }
            decide(
                SettingKind.PAPER,
                if (listed.isEmpty()) Outcome.BY_PRINTER else Outcome.APPROXIMATED,
                if (listed.isEmpty()) null else "The printer does not list this size; it may substitute another",
            )
            return wanted
        }
        caps.defaultMedia?.let { return it }
        listed.firstOrNull()?.let { return it }
        decide(SettingKind.PAPER, Outcome.BY_PRINTER, "The printer reported no paper sizes; assuming A4")
        return MediaSize.A4
    }

    // --- can the printer do the layout itself? ------------------------------------------------

    /** True when every layout-affecting setting can be expressed to a PDF-capable printer, so the original PDF can be sent unchanged. */
    private fun printerCanLayOutNatively(settings: PrintSettings, caps: PrinterCapabilities, wantedSides: Sides): Boolean {
        val pages = settings.pages
        val pagesOk = !settings.reverseOrder &&
            pages.parity == PageSelection.Parity.ALL &&
            (pages.ranges == null || (caps.supportsPageRanges && isAscendingAndDisjoint(pages.ranges!!)))
        val bookletOk = !settings.booklet || caps.bookletMaker
        val nUp = settings.pagesPerSheet.count
        val nUpOk = nUp == 1 || (nUp in caps.numberUp && settings.orientation == Orientation.AUTO)
        val scalingOk = when (val s = settings.scaling) {
            Scaling.ShrinkToFit -> true // the printer's default behaviour
            Scaling.FitToPage -> PrinterScaling.FIT in caps.printerScaling
            Scaling.Fill -> PrinterScaling.FILL in caps.printerScaling
            Scaling.ActualSize -> PrinterScaling.NONE in caps.printerScaling
            is Scaling.Custom -> s.percent < 0 // a custom zoom can never be asked of a printer
        }
        val orientationOk = settings.orientation == Orientation.AUTO
        val marginsOk = settings.margins == MarginSetting.PrinterDefault
        val sidesOk = !wantedSides.isDuplex || wantedSides in caps.sides
        return pagesOk && bookletOk && nUpOk && scalingOk && orientationOk && marginsOk && sidesOk
    }

    private fun isAscendingAndDisjoint(ranges: List<IntRange>): Boolean =
        ranges.zipWithNext().all { (a, b) -> a.last < b.first }

    // --- pass-through ----------------------------------------------------------------------------

    private fun passThrough(
        settings: PrintSettings,
        caps: PrinterCapabilities,
        jobName: String,
        sheet: MediaSize,
        layout: LayoutPlan,
        wantedSides: Sides,
        degraded: Boolean,
        decisions: MutableList<Decision>,
    ): PrintPlan {
        fun decide(kind: SettingKind, outcome: Outcome, detail: String? = null) {
            decisions += Decision(kind, outcome, detail)
        }

        // Copies and collation
        val copiesCapable = (caps.maxCopies ?: 1) >= settings.copies
        if (settings.copies > 1) {
            decide(SettingKind.COPIES, if (copiesCapable) Outcome.BY_PRINTER else Outcome.APPROXIMATED, if (copiesCapable) null else "The printer may print only one copy")
            if (!settings.collate) decide(SettingKind.COLLATION, Outcome.BY_PRINTER) else decide(SettingKind.COLLATION, Outcome.BY_PRINTER, "Collation is left to the printer")
        }

        // Sides
        val sides = if (wantedSides.isDuplex && wantedSides in caps.sides) wantedSides else Sides.ONE_SIDED
        if (wantedSides.isDuplex) {
            if (sides == wantedSides) {
                decide(SettingKind.SIDES, Outcome.BY_PRINTER)
            } else {
                decide(SettingKind.SIDES, Outcome.IGNORED, "The printer cannot print on both sides and this document cannot be split into passes")
            }
        }

        // Pages, order, layout features the printer handles
        val pageRanges = if (settings.pages.ranges != null && caps.supportsPageRanges && isAscendingAndDisjoint(settings.pages.ranges!!)) settings.pages.ranges else null
        if (settings.pages != PageSelection.ALL) {
            if (pageRanges != null && settings.pages.parity == PageSelection.Parity.ALL) {
                decide(SettingKind.PAGES, Outcome.BY_PRINTER)
            } else {
                decide(SettingKind.PAGES, Outcome.IGNORED, "This page selection cannot be sent to the printer; all pages will print")
            }
        }
        if (settings.reverseOrder) decide(SettingKind.REVERSE, Outcome.IGNORED, "Reverse order is not available when printing the PDF directly")
        val numberUp = settings.pagesPerSheet.count.takeIf { it > 1 && it in caps.numberUp }
        if (settings.pagesPerSheet.count > 1) {
            if (numberUp != null) decide(SettingKind.PAGES_PER_SHEET, Outcome.BY_PRINTER) else decide(SettingKind.PAGES_PER_SHEET, Outcome.IGNORED, "The printer cannot print ${settings.pagesPerSheet.count} pages per sheet")
        }
        val booklet = settings.booklet && caps.bookletMaker
        if (settings.booklet) {
            if (booklet) decide(SettingKind.BOOKLET, Outcome.BY_PRINTER) else decide(SettingKind.BOOKLET, Outcome.IGNORED, "The printer cannot make booklets")
        }

        val scaling = when (settings.scaling) {
            Scaling.ShrinkToFit -> Scaling.ShrinkToFit
            Scaling.FitToPage -> Scaling.FitToPage.takeIf { PrinterScaling.FIT in caps.printerScaling }
            Scaling.Fill -> Scaling.Fill.takeIf { PrinterScaling.FILL in caps.printerScaling }
            Scaling.ActualSize -> Scaling.ActualSize.takeIf { PrinterScaling.NONE in caps.printerScaling }
            is Scaling.Custom -> null
        }
        if (settings.scaling != Scaling.ShrinkToFit) {
            if (scaling != null) decide(SettingKind.SCALING, Outcome.BY_PRINTER) else decide(SettingKind.SCALING, Outcome.IGNORED, "The printer does not offer this scaling for PDF")
        }
        if (settings.orientation != Orientation.AUTO) decide(SettingKind.ORIENTATION, Outcome.IGNORED, "Orientation follows the document")
        if (settings.margins != MarginSetting.PrinterDefault) decide(SettingKind.MARGINS, Outcome.IGNORED, "Margins follow the document")

        val common = commonPrinterSettings(settings, caps, jobName, decisions)
        val printer = common.copy(
            copies = settings.copies,
            sides = sides,
            paper = sheet.takeIf { settings.paper != null },
            pageRanges = pageRanges.takeIf { settings.pages.parity == PageSelection.Parity.ALL },
            numberUp = numberUp,
            scaling = scaling,
            bookletMaker = booklet,
            resolutionDpi = settings.resolutionDpi?.takeIf { it in caps.resolutionsDpi },
        )
        if (degraded) decide(SettingKind.PAGES, Outcome.APPROXIMATED, "Printed through the PDF path with the printer's own capabilities only")
        return PrintPlan(
            route = RouteKind.PASS_THROUGH,
            format = DocumentFormat.PDF,
            layout = layout,
            raster = null,
            printer = printer,
            passes = emptyList(),
            manualDuplex = null,
            decisions = decisions,
        )
    }

    // --- raster -----------------------------------------------------------------------------------

    /** The raster format Platen can produce and the printer accepts, best first. Only PWG Raster so far. */
    private fun rasterFormat(caps: PrinterCapabilities): DocumentFormat? =
        DocumentFormat.PWG_RASTER.takeIf { caps.supports(it) }

    private fun raster(
        settings: PrintSettings,
        caps: PrinterCapabilities,
        jobName: String,
        sheet: MediaSize,
        layout: LayoutPlan,
        wantedSides: Sides,
        decisions: MutableList<Decision>,
    ): PrintPlan {
        fun decide(kind: SettingKind, outcome: Outcome, detail: String? = null) {
            decisions += Decision(kind, outcome, detail)
        }

        // What the layout planner already did for us.
        if (settings.pages != PageSelection.ALL) decide(SettingKind.PAGES, Outcome.BY_APP)
        if (settings.reverseOrder && !settings.booklet) decide(SettingKind.REVERSE, Outcome.BY_APP)
        if (settings.pagesPerSheet.count > 1) decide(SettingKind.PAGES_PER_SHEET, Outcome.BY_APP)
        if (settings.booklet) decide(SettingKind.BOOKLET, Outcome.BY_APP)
        decide(SettingKind.SCALING, Outcome.BY_APP)
        if (settings.orientation != Orientation.AUTO) decide(SettingKind.ORIENTATION, Outcome.BY_APP)
        decide(SettingKind.MARGINS, Outcome.BY_APP)

        // Colour and pixel format
        val colorAvailable = ColorMode.COLOR in caps.colorModes || ColorMode.AUTO in caps.colorModes
        val wantColor = settings.color != ColorMode.MONOCHROME && (colorAvailable || caps.colorModes.isEmpty())
        if (settings.color == ColorMode.COLOR && !wantColor) decide(SettingKind.COLOR, Outcome.APPROXIMATED, "The printer prints in black and white only")
        val types = caps.raster?.colorTypes?.map { it.lowercase() }.orEmpty().ifEmpty { listOf("srgb_8", "sgray_8") }
        val type = when {
            wantColor && "srgb_8" in types -> "srgb_8"
            "sgray_8" in types -> "sgray_8"
            "srgb_8" in types -> "srgb_8" // grey rendered as RGB
            else -> throw PlanningException("The printer's raster colour types (${types.joinToString()}) are not supported yet")
        }
        val pixelFormat = if (type == "sgray_8") RasterPixelFormat.GRAY8 else RasterPixelFormat.RGB24

        // Resolution
        val available = caps.raster?.resolutionsDpi.orEmpty().ifEmpty { caps.resolutionsDpi }.ifEmpty { listOf(DEFAULT_RASTER_DPI) }
        val dpi = when {
            settings.resolutionDpi != null -> nearest(available, settings.resolutionDpi!!)
            settings.quality == Quality.DRAFT -> available.min()
            settings.quality == Quality.HIGH -> available.max()
            else -> nearest(available, DEFAULT_RASTER_DPI)
        }
        settings.resolutionDpi?.let { if (it != dpi) decide(SettingKind.RESOLUTION, Outcome.APPROXIMATED, "Using $dpi dpi, the closest the printer offers") else decide(SettingKind.RESOLUTION, Outcome.BY_PRINTER) }

        // Sides: printer duplex, manual duplex, or one-sided
        val printerDuplex = wantedSides.isDuplex && wantedSides in caps.sides
        val manualDuplex = wantedSides.isDuplex && !printerDuplex
        if (wantedSides.isDuplex) {
            if (printerDuplex) decide(SettingKind.SIDES, Outcome.BY_PRINTER) else decide(SettingKind.SIDES, Outcome.BY_APP, "Manual duplex: print the front sides, reload the paper, then print the back sides")
        }

        // Copies: the printer's if it can and collation is not needed, else Platen sends the pages again.
        val printerCanCopy = (caps.maxCopies ?: 1) >= settings.copies
        val appCopies = if (settings.copies > 1 && (settings.collate || !printerCanCopy)) settings.copies else 1
        if (settings.copies > 1) {
            if (appCopies > 1) {
                decide(SettingKind.COPIES, Outcome.BY_APP, "Pages are sent $appCopies times so the copies come out in order")
                decide(SettingKind.COLLATION, Outcome.BY_APP)
            } else {
                decide(SettingKind.COPIES, Outcome.BY_PRINTER)
                decide(SettingKind.COLLATION, Outcome.BY_PRINTER, "Pages are repeated by the printer, not collated")
            }
        }

        val faces = buildFaces(layout.sides.size, appCopies, padToEven = wantedSides.isDuplex)
        val passes = when {
            manualDuplex -> listOf(
                Pass(faces.filterIndexed { i, _ -> i % 2 == 0 }, Pass.Role.FRONTS),
                Pass(faces.filterIndexed { i, _ -> i % 2 == 1 }, Pass.Role.BACKS),
            )
            else -> listOf(Pass(faces))
        }

        val common = commonPrinterSettings(settings, caps, jobName, decisions)
        val printer = common.copy(
            copies = if (appCopies > 1) 1 else settings.copies,
            sides = if (printerDuplex) wantedSides else Sides.ONE_SIDED,
            colorMode = if (wantColor) ColorMode.COLOR else ColorMode.MONOCHROME,
            resolutionDpi = dpi,
            paper = sheet,
            scaling = Scaling.ActualSize, // already scaled by Platen
            orientation = Orientation.PORTRAIT, // the bitmap is in the sheet's native orientation
        )
        return PrintPlan(
            route = RouteKind.RASTER,
            format = DocumentFormat.PWG_RASTER,
            layout = layout,
            raster = RasterParams(
                dpi = dpi,
                pixelFormat = pixelFormat,
                pwgType = type,
                printerDuplex = printerDuplex,
                tumble = printerDuplex && wantedSides == Sides.TWO_SIDED_SHORT_EDGE,
                sheetBack = SheetBack.fromKeyword(caps.raster?.sheetBack),
            ),
            printer = printer,
            passes = passes,
            manualDuplex = if (manualDuplex) ManualDuplex(wantedSides) else null,
            decisions = decisions,
        )
    }

    /** All faces in order, [copies] times over; with duplex each copy is padded to an even count so sheets pair up. */
    private fun buildFaces(sideCount: Int, copies: Int, padToEven: Boolean): List<Face> {
        val one = List(sideCount) { Face(it) }.let { if (padToEven && it.size % 2 == 1) it + Face(null) else it }
        return List(copies) { one }.flatten()
    }

    private fun nearest(values: List<Int>, wanted: Int): Int = values.minBy { abs(it - wanted) }

    // --- settings both routes share ---------------------------------------------------------------

    private fun commonPrinterSettings(
        settings: PrintSettings,
        caps: PrinterCapabilities,
        jobName: String,
        decisions: MutableList<Decision>,
    ): PrinterJobSettings {
        fun decide(kind: SettingKind, outcome: Outcome, detail: String? = null) {
            decisions += Decision(kind, outcome, detail)
        }

        var quality: Quality? = null
        if (settings.economy) {
            if (Quality.DRAFT in caps.qualities) {
                quality = Quality.DRAFT
                decide(SettingKind.ECONOMY, Outcome.APPROXIMATED, "Approximated with draft quality")
            } else {
                decide(SettingKind.ECONOMY, Outcome.IGNORED, "The printer has no toner-saving mode")
            }
        }
        if (quality == null && settings.quality != Quality.NORMAL) {
            if (settings.quality in caps.qualities) {
                quality = settings.quality
                decide(SettingKind.QUALITY, Outcome.BY_PRINTER)
            } else if (caps.qualities.isNotEmpty()) {
                decide(SettingKind.QUALITY, Outcome.IGNORED, "The printer does not offer this quality")
            }
        } else if (quality == null && settings.quality in caps.qualities) {
            quality = settings.quality
        }

        val tray = settings.tray?.let { t ->
            if (caps.trays.any { it.id == t.id }) {
                decide(SettingKind.TRAY, Outcome.BY_PRINTER)
                t
            } else {
                decide(SettingKind.TRAY, Outcome.IGNORED, "The printer does not list this tray")
                null
            }
        }
        val type = settings.paperType?.let { t ->
            if (caps.mediaTypes.any { it.id == t.id }) {
                decide(SettingKind.PAPER_TYPE, Outcome.BY_PRINTER)
                t
            } else {
                decide(SettingKind.PAPER_TYPE, Outcome.IGNORED, "The printer does not list this paper type")
                null
            }
        }
        val bin = settings.outputBin?.let { b ->
            if (b in caps.outputBins) {
                decide(SettingKind.OUTPUT_BIN, Outcome.BY_PRINTER)
                b
            } else {
                decide(SettingKind.OUTPUT_BIN, Outcome.IGNORED, "The printer does not list this output bin")
                null
            }
        }
        val colorMode = when {
            settings.color == ColorMode.AUTO -> null
            settings.color in caps.colorModes -> settings.color
            settings.color == ColorMode.MONOCHROME -> ColorMode.MONOCHROME
            else -> null
        }
        return PrinterJobSettings(jobName = jobName, quality = quality, tray = tray, paperType = type, outputBin = bin, colorMode = colorMode)
    }
}
