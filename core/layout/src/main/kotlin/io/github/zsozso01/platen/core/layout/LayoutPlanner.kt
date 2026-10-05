package io.github.zsozso01.platen.core.layout

import io.github.zsozso01.platen.core.model.BookletBinding
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PagesPerSheet
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides
import kotlin.math.max
import kotlin.math.min

/**
 * Decides where every selected source page goes: which pages, in which order, how big, rotated or
 * not, on which side of which sheet. Pure geometry: no rendering and no printer protocol, so it is
 * the single source of truth for both the raster path and the vector-PDF path.
 *
 * Coordinates are points, origin top-left, y down. A sheet is described in its *native* orientation
 * (as fed to the printer). To use a sheet "sideways" the planner works on a *canvas* turned a quarter
 * turn clockwise and folds that turn into every [Placement.transform].
 */
public object LayoutPlanner {
    /** Space between pages when several share a sheet, in points. */
    private const val GUTTER_POINTS = 8.0

    private const val HUNDREDTHS_MM_TO_POINTS = 72.0 / 2540.0

    /**
     * @param pages every page of the source document, index 0 being page 1
     * @param sheet the paper as fed (its native width and height, usually portrait)
     * @param unprintable the border the printer physically cannot print on
     */
    public fun plan(
        pages: List<PageGeometry>,
        settings: PrintSettings,
        sheet: MediaSize,
        unprintable: Margins = Margins.NONE,
    ): LayoutPlan {
        val notes = linkedSetOf<LayoutPlan.Note>()
        val sheetRect = Rect(0.0, 0.0, sheet.widthPoints, sheet.heightPoints)
        val area = printableArea(sheetRect, settings, unprintable)

        var selected = settings.pages.resolve(pages.size)
        if (selected.isEmpty()) notes += LayoutPlan.Note.NOTHING_SELECTED
        if (settings.booklet) return planBooklet(pages, selected, settings, sheet, sheetRect, area, notes)

        if (settings.reverseOrder) selected = selected.reversed()
        val perSheet = settings.pagesPerSheet
        val sides = selected.chunked(perSheet.count).map { chunk ->
            Side(placeCells(chunk, pages, settings, perSheet, sheetRect, area, forceLandscape = false, notes))
        }
        return LayoutPlan(sheet, area, sides, requiresDuplex = false, requiredSides = null, notes = notes.toList())
    }

    private fun printableArea(sheetRect: Rect, settings: PrintSettings, unprintable: Margins): Rect {
        fun pt(v: Int) = v * HUNDREDTHS_MM_TO_POINTS
        val inset = when (val m = settings.margins) {
            MarginSetting.PrinterDefault -> unprintable
            MarginSetting.None -> Margins.NONE
            is MarginSetting.Custom -> Margins(
                top = max(unprintable.top, m.margins.top),
                right = max(unprintable.right, m.margins.right),
                bottom = max(unprintable.bottom, m.margins.bottom),
                left = max(unprintable.left, m.margins.left),
            )
        }
        return sheetRect.inset(pt(inset.left), pt(inset.top), pt(inset.right), pt(inset.bottom))
    }

    // --- booklet --------------------------------------------------------------------------------

    private fun planBooklet(
        pages: List<PageGeometry>,
        selected: List<Int>,
        settings: PrintSettings,
        sheet: MediaSize,
        sheetRect: Rect,
        area: Rect,
        notes: MutableSet<LayoutPlan.Note>,
    ): LayoutPlan {
        val n = ((selected.size + 3) / 4) * 4
        if (n != selected.size) notes += LayoutPlan.Note.BOOKLET_PADDED
        // Position p (1-based) in the folded booklet -> source page, or null for padding.
        fun page(p: Int): Int? = selected.getOrNull(p - 1)
        val rtl = settings.bookletBinding == BookletBinding.RIGHT
        val perSheet = PagesPerSheet(
            count = 2,
            order = if (rtl) PagesPerSheet.Order.RIGHT_TO_LEFT_THEN_DOWN else PagesPerSheet.Order.LEFT_TO_RIGHT_THEN_DOWN,
        )
        val sides = mutableListOf<Side>()
        for (i in 0 until n / 4) {
            // Saddle-stitch imposition: the outermost sheet carries the first and last pages.
            val front = listOf(page(n - 2 * i), page(1 + 2 * i))
            val back = listOf(page(2 + 2 * i), page(n - 1 - 2 * i))
            for (cells in listOf(front, back)) {
                sides += Side(placeCells(cells, pages, settings, perSheet, sheetRect, area, forceLandscape = true, notes))
            }
        }
        // The canvas is landscape on a portrait sheet, so the back must flip on the short edge.
        val required = if (sheet.isLandscape) Sides.TWO_SIDED_LONG_EDGE else Sides.TWO_SIDED_SHORT_EDGE
        return LayoutPlan(sheet, area, sides, requiresDuplex = true, requiredSides = required, notes = notes.toList())
    }

    // --- canvases and grids ---------------------------------------------------------------------

    /**
     * The printable area as the layout sees it: possibly turned a quarter turn so that "landscape"
     * content can use a portrait sheet. [toSheet] maps canvas coordinates to sheet coordinates.
     */
    private class Canvas(val rect: Rect, val toSheet: Affine, val turned: Boolean)

    private fun candidateCanvases(
        orientation: Orientation,
        sheetRect: Rect,
        area: Rect,
        forceLandscape: Boolean,
    ): List<Canvas> {
        val natural = Canvas(area, Affine.IDENTITY, turned = false)
        // Quarter turn clockwise: canvas (x', y') -> sheet (W - y', x'). The canvas is H wide and W tall, so
        // the printable area seen from the canvas is x' in [area.top, area.bottom], y' in [W - area.right, W - area.left].
        val turned = Canvas(
            rect = Rect(area.top, sheetRect.width - area.right, area.bottom, sheetRect.width - area.left),
            toSheet = Affine(0.0, 1.0, -1.0, 0.0, sheetRect.width, 0.0),
            turned = true,
        )
        fun orientationOf(c: Canvas) = if (c.rect.width > c.rect.height) Orientation.LANDSCAPE else Orientation.PORTRAIT
        val all = listOf(natural, turned)
        return when {
            forceLandscape -> all.filter { orientationOf(it) == Orientation.LANDSCAPE }
            orientation == Orientation.AUTO -> all
            else -> all.filter { orientationOf(it) == orientation }
        }.ifEmpty { listOf(natural) }
    }

    private class Arrangement(val canvas: Canvas, val cols: Int, val rows: Int, val pageRotation: Int, val score: Double)

    /** Every factor pair of [n] as a grid, e.g. 6 -> 1x6, 2x3, 3x2, 6x1. */
    private fun gridsFor(n: Int): List<Pair<Int, Int>> = (1..n).filter { n % it == 0 }.map { it to n / it }

    private fun fitScale(page: PageGeometry, rotation: Int, cellW: Double, cellH: Double): Double {
        val w = if (rotation % 180 == 0) page.widthPoints else page.heightPoints
        val h = if (rotation % 180 == 0) page.heightPoints else page.widthPoints
        return min(cellW / w, cellH / h)
    }

    private fun fillScale(page: PageGeometry, rotation: Int, cellW: Double, cellH: Double): Double {
        val w = if (rotation % 180 == 0) page.widthPoints else page.heightPoints
        val h = if (rotation % 180 == 0) page.heightPoints else page.widthPoints
        return max(cellW / w, cellH / h)
    }

    private fun cellSize(canvas: Canvas, cols: Int, rows: Int): Pair<Double, Double> =
        ((canvas.rect.width - GUTTER_POINTS * (cols - 1)) / cols) to ((canvas.rect.height - GUTTER_POINTS * (rows - 1)) / rows)

    /** Tries every canvas, grid shape and (in AUTO) page rotation, keeping the one that lets pages be largest. */
    private fun arrange(
        canvases: List<Canvas>,
        geometry: List<PageGeometry?>,
        settings: PrintSettings,
        count: Int,
    ): Arrangement {
        val auto = settings.orientation == Orientation.AUTO
        var best: Arrangement? = null
        for (canvas in canvases) {
            for ((cols, rows) in gridsFor(count)) {
                for (rotation in if (auto) listOf(0, 90) else listOf(0)) {
                    val (cellW, cellH) = cellSize(canvas, cols, rows)
                    if (cellW <= 0 || cellH <= 0) continue
                    val minFit = geometry.filterNotNull().minOfOrNull { fitScale(it, rotation, cellW, cellH) } ?: 1.0
                    // Prefer larger pages; on a tie prefer not rotating pages, then not turning the canvas.
                    val score = minFit - rotation * 1e-6 - (if (canvas.turned) 1e-7 else 0.0)
                    if (best == null || score > best.score) best = Arrangement(canvas, cols, rows, rotation, score)
                }
            }
        }
        return best ?: Arrangement(canvases.first(), 1, 1, 0, 0.0)
    }

    private fun placeCells(
        chunk: List<Int?>,
        pages: List<PageGeometry>,
        settings: PrintSettings,
        perSheet: PagesPerSheet,
        sheetRect: Rect,
        area: Rect,
        forceLandscape: Boolean,
        notes: MutableSet<LayoutPlan.Note>,
    ): List<Placement> {
        val geometry = chunk.map { n -> n?.let { pages[it - 1] } }
        val canvases = candidateCanvases(settings.orientation, sheetRect, area, forceLandscape)
        val arrangement = arrange(canvases, geometry, settings, perSheet.count)
        val canvas = arrangement.canvas
        val (cellW, cellH) = cellSize(canvas, arrangement.cols, arrangement.rows)
        val multi = perSheet.count > 1
        val placements = mutableListOf<Placement>()

        chunk.forEachIndexed { index, pageNumber ->
            val page = geometry[index]
            if (pageNumber == null || page == null) return@forEachIndexed // blank cell
            val (col, row) = cellOf(index, arrangement.cols, arrangement.rows, perSheet.order)
            val left = canvas.rect.left + col * (cellW + GUTTER_POINTS)
            val top = canvas.rect.top + row * (cellH + GUTTER_POINTS)
            val cell = Rect(left, top, left + cellW, top + cellH)

            val rotation = arrangement.pageRotation
            val fit = fitScale(page, rotation, cellW, cellH)
            val scale = when (val s = settings.scaling) {
                Scaling.ShrinkToFit -> min(1.0, fit)
                Scaling.FitToPage -> fit
                Scaling.Fill -> fillScale(page, rotation, cellW, cellH)
                // Several pages never fit at actual size, so cells fall back to shrinking.
                Scaling.ActualSize -> if (multi) min(1.0, fit) else 1.0
                is Scaling.Custom -> s.percent / 100.0
            }

            val inCanvas = Affine.placeCentred(page, scale, rotation, cell)
            val boundsInCanvas = inCanvas.mapBounds(Rect(0.0, 0.0, page.widthPoints, page.heightPoints))
            if (settings.orientation == Orientation.AUTO && (canvas.turned || rotation != 0)) {
                notes += LayoutPlan.Note.PAGES_ROTATED
            }
            if (boundsInCanvas.width > cell.width + EPS || boundsInCanvas.height > cell.height + EPS) {
                notes += LayoutPlan.Note.CONTENT_CROPPED
            }
            placements += Placement(
                sourcePage = pageNumber,
                transform = canvas.toSheet.after(inCanvas),
                clip = canvas.toSheet.mapBounds(cell),
                bounds = canvas.toSheet.mapBounds(boundsInCanvas),
                drawBorder = perSheet.border && multi,
                scale = scale,
            )
        }
        return placements
    }

    private fun cellOf(index: Int, cols: Int, rows: Int, order: PagesPerSheet.Order): Pair<Int, Int> = when (order) {
        PagesPerSheet.Order.LEFT_TO_RIGHT_THEN_DOWN -> (index % cols) to (index / cols)
        PagesPerSheet.Order.RIGHT_TO_LEFT_THEN_DOWN -> (cols - 1 - index % cols) to (index / cols)
        PagesPerSheet.Order.TOP_TO_BOTTOM_THEN_RIGHT -> (index / rows) to (index % rows)
        PagesPerSheet.Order.BOTTOM_TO_TOP_THEN_LEFT -> (cols - 1 - index / rows) to (rows - 1 - index % rows)
    }

    private const val EPS = 0.01
}
