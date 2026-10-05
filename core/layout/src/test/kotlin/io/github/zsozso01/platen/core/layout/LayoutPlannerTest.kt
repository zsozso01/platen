package io.github.zsozso01.platen.core.layout

import io.github.zsozso01.platen.core.model.BookletBinding
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PagesPerSheet
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LayoutPlannerTest {
    private val a4 = MediaSize.A4
    private val a4Page = PageGeometry(a4.widthPoints, a4.heightPoints)
    private val letterPage = PageGeometry(MediaSize.LETTER.widthPoints, MediaSize.LETTER.heightPoints)
    private val noMargins = PrintSettings(margins = MarginSetting.None)

    private fun assertNear(expected: Double, actual: Double, eps: Double = 0.01, msg: String = "") =
        assertTrue(kotlin.math.abs(expected - actual) <= eps, "$msg expected $expected but was $actual")

    private fun plan(pages: List<PageGeometry>, settings: PrintSettings, sheet: MediaSize = a4, unprintable: Margins = Margins.NONE) =
        LayoutPlanner.plan(pages, settings, sheet, unprintable)

    private fun LayoutPlan.pageOrder() = sides.map { s -> s.placements.map { it.sourcePage } }

    // --- single page: scaling modes -----------------------------------------------------------

    @Test
    fun `an A4 page on A4 paper is placed at identity`() {
        val p = plan(listOf(a4Page), noMargins.copy(scaling = Scaling.FitToPage)).sides.single().placements.single()
        assertEquals(1, p.sourcePage)
        assertNear(1.0, p.scale)
        assertNear(0.0, p.transform.mapX(0.0, 0.0))
        assertNear(0.0, p.transform.mapY(0.0, 0.0))
        assertNear(a4.widthPoints, p.bounds.width)
    }

    @Test
    fun `shrink-to-fit shrinks oversize pages, never enlarges small ones`() {
        val shrunk = plan(listOf(letterPage), noMargins).sides.single().placements.single()
        assertNear(a4.widthPoints / letterPage.widthPoints, shrunk.scale, 1e-6) // width is the limiting side
        assertNear(a4.widthPoints, shrunk.bounds.width)
        assertNear(a4.widthPoints / 2, shrunk.bounds.centerX)

        val a5 = PageGeometry(MediaSize.A5.widthPoints, MediaSize.A5.heightPoints)
        val small = plan(listOf(a5), noMargins).sides.single().placements.single()
        assertNear(1.0, small.scale)
        assertNear(a4.widthPoints / 2, small.bounds.centerX)
        assertNear(a4.heightPoints / 2, small.bounds.centerY)
    }

    @Test
    fun `fit-to-page enlarges small pages`() {
        val a5 = PageGeometry(MediaSize.A5.widthPoints, MediaSize.A5.heightPoints)
        val p = plan(listOf(a5), noMargins.copy(scaling = Scaling.FitToPage)).sides.single().placements.single()
        assertNear(a4.widthPoints / a5.widthPoints, p.scale, 0.01)
    }

    @Test
    fun `fill covers the area and reports cropping`() {
        val result = plan(listOf(letterPage), noMargins.copy(scaling = Scaling.Fill))
        val p = result.sides.single().placements.single()
        assertNear(a4.heightPoints / letterPage.heightPoints, p.scale, 1e-6)
        assertTrue(p.bounds.width >= a4.widthPoints - 0.01 && p.bounds.height >= a4.heightPoints - 0.01)
        assertTrue(LayoutPlan.Note.CONTENT_CROPPED in result.notes)
    }

    @Test
    fun `actual size keeps one to one and custom scale applies its percentage`() {
        val actual = plan(listOf(letterPage), noMargins.copy(scaling = Scaling.ActualSize)).sides.single().placements.single()
        assertNear(1.0, actual.scale)
        val half = plan(listOf(a4Page), noMargins.copy(scaling = Scaling.Custom(50))).sides.single().placements.single()
        assertNear(0.5, half.scale)
        assertNear(a4.widthPoints / 2, half.bounds.width)
        assertNear(a4.widthPoints / 2, half.bounds.centerX)
    }

    // --- margins ------------------------------------------------------------------------------

    @Test
    fun `printer-default margins use the unprintable border`() {
        val unprintable = Margins(top = 296, right = 296, bottom = 1270, left = 296) // DeskJet-like
        val result = plan(listOf(a4Page), PrintSettings(scaling = Scaling.FitToPage), unprintable = unprintable)
        val pt = 72.0 / 2540.0
        assertNear(296 * pt, result.printableArea.left)
        assertNear(a4.heightPoints - 1270 * pt, result.printableArea.bottom)
        val p = result.sides.single().placements.single()
        assertTrue(p.bounds.bottom <= result.printableArea.bottom + 0.01)
        assertTrue(p.scale < 1.0)
    }

    @Test
    fun `custom margins are never smaller than the unprintable border`() {
        val unprintable = Margins.uniform(500) // 5 mm
        val bigger = plan(listOf(a4Page), PrintSettings(margins = MarginSetting.Custom(Margins.uniform(2000)), scaling = Scaling.FitToPage), unprintable = unprintable)
        assertNear(2000 * 72.0 / 2540, bigger.printableArea.left)
        val smaller = plan(listOf(a4Page), PrintSettings(margins = MarginSetting.Custom(Margins.uniform(100)), scaling = Scaling.FitToPage), unprintable = unprintable)
        assertNear(500 * 72.0 / 2540, smaller.printableArea.left)
        val none = plan(listOf(a4Page), PrintSettings(margins = MarginSetting.None), unprintable = unprintable)
        assertNear(0.0, none.printableArea.left)
    }

    // --- orientation --------------------------------------------------------------------------

    private val landscapePage = PageGeometry(a4.heightPoints, a4.widthPoints)

    @Test
    fun `auto orientation turns a landscape page to use a portrait sheet`() {
        val result = plan(listOf(landscapePage), noMargins.copy(scaling = Scaling.FitToPage))
        val p = result.sides.single().placements.single()
        assertNear(1.0, p.scale, 1e-6)
        // The page's top-left corner lands at the sheet's top-right: a clockwise quarter turn.
        assertNear(a4.widthPoints, p.transform.mapX(0.0, 0.0))
        assertNear(0.0, p.transform.mapY(0.0, 0.0))
        // ... and its top-right corner at the sheet's bottom-right.
        assertNear(a4.widthPoints, p.transform.mapX(landscapePage.widthPoints, 0.0))
        assertNear(a4.heightPoints, p.transform.mapY(landscapePage.widthPoints, 0.0))
        assertTrue(LayoutPlan.Note.PAGES_ROTATED in result.notes)
    }

    @Test
    fun `forced portrait never turns the page`() {
        val p = plan(listOf(landscapePage), noMargins.copy(scaling = Scaling.FitToPage, orientation = Orientation.PORTRAIT)).sides.single().placements.single()
        assertNear(a4.widthPoints / a4.heightPoints, p.scale, 1e-6)
        assertNear(0.0, p.transform.b)
    }

    @Test
    fun `forced landscape turns a portrait page sideways and shrinks it`() {
        val result = plan(listOf(a4Page), noMargins.copy(scaling = Scaling.FitToPage, orientation = Orientation.LANDSCAPE))
        val p = result.sides.single().placements.single()
        assertNear(a4.widthPoints / a4.heightPoints, p.scale, 1e-6)
        assertFalse(LayoutPlan.Note.PAGES_ROTATED in result.notes, "an explicit choice is not news")
        assertNear(1.0, kotlin.math.abs(p.transform.b / p.scale)) // rotated: x-axis maps to the sheet's y axis
    }

    // --- page selection ----------------------------------------------------------------------

    @Test
    fun `selection reverse and parity decide the order`() {
        val pages = List(5) { a4Page }
        assertEquals(listOf(listOf(1), listOf(2), listOf(3), listOf(4), listOf(5)), plan(pages, noMargins).pageOrder())
        assertEquals(listOf(listOf(4), listOf(3), listOf(2)), plan(pages, noMargins.copy(pages = PageSelection(listOf(2..4)), reverseOrder = true)).pageOrder())
        assertEquals(listOf(listOf(1), listOf(3), listOf(5)), plan(pages, noMargins.copy(pages = PageSelection(parity = PageSelection.Parity.ODD_ONLY))).pageOrder())
        assertEquals(listOf(listOf(4), listOf(2)), plan(pages, noMargins.copy(pages = PageSelection(parity = PageSelection.Parity.EVEN_ONLY), reverseOrder = true)).pageOrder())
    }

    @Test
    fun `an empty selection is reported and prints nothing`() {
        val result = plan(List(3) { a4Page }, noMargins.copy(pages = PageSelection(listOf(9..12))))
        assertTrue(result.isEmpty)
        assertTrue(LayoutPlan.Note.NOTHING_SELECTED in result.notes)
        assertTrue(plan(emptyList(), noMargins).isEmpty)
    }

    @Test
    fun `pages of different sizes are scaled individually`() {
        val result = plan(listOf(a4Page, letterPage), noMargins)
        val (first, second) = result.sides.map { it.placements.single() }
        assertNear(1.0, first.scale)
        assertTrue(second.scale < 1.0)
    }

    // --- pages per sheet ---------------------------------------------------------------------

    private fun nUp(count: Int, order: PagesPerSheet.Order = PagesPerSheet.Order.LEFT_TO_RIGHT_THEN_DOWN) =
        noMargins.copy(pagesPerSheet = PagesPerSheet(count, order, border = true))

    @Test
    fun `two-up uses the sheet sideways so both pages are large`() {
        val result = plan(listOf(a4Page, a4Page), nUp(2))
        val side = result.sides.single()
        assertEquals(listOf(1, 2), side.placements.map { it.sourcePage })
        val (a, b) = side.placements
        // Two A4 portrait pages share one A4 sheet turned sideways: each at about 70%.
        assertNear(0.70, a.scale, 0.01)
        assertNear(a.scale, b.scale, 1e-9)
        assertTrue(a.bounds.intersect(b.bounds).isEmpty(), "pages must not overlap")
        side.placements.forEach {
            assertTrue(it.bounds.left >= -0.01 && it.bounds.right <= a4.widthPoints + 0.01)
            assertTrue(it.bounds.top >= -0.01 && it.bounds.bottom <= a4.heightPoints + 0.01)
            assertTrue(it.drawBorder)
        }
        assertTrue(LayoutPlan.Note.PAGES_ROTATED in result.notes)
    }

    @Test
    fun `four-up fills a two by two grid in reading order`() {
        val side = plan(List(4) { a4Page }, nUp(4)).sides.single()
        assertEquals(listOf(1, 2, 3, 4), side.placements.map { it.sourcePage })
        val (p1, p2, p3, p4) = side.placements
        assertNear(0.4934, p1.scale, 0.001)
        assertTrue(p2.bounds.left > p1.bounds.left && kotlin.math.abs(p2.bounds.top - p1.bounds.top) < 0.01, "page 2 is right of page 1")
        assertTrue(p3.bounds.top > p1.bounds.top && kotlin.math.abs(p3.bounds.left - p1.bounds.left) < 0.01, "page 3 is below page 1")
        assertTrue(p4.bounds.left > p3.bounds.left && p4.bounds.top > p2.bounds.top)
    }

    @Test
    fun `column-major order goes down first`() {
        val side = plan(List(4) { a4Page }, nUp(4, PagesPerSheet.Order.TOP_TO_BOTTOM_THEN_RIGHT)).sides.single()
        val (p1, p2, p3, _) = side.placements
        assertTrue(p2.bounds.top > p1.bounds.top, "page 2 is below page 1")
        assertTrue(p3.bounds.left > p1.bounds.left, "page 3 starts the next column")
    }

    @Test
    fun `a partly filled last sheet and sheet counts`() {
        val result = plan(List(10) { a4Page }, nUp(4))
        assertEquals(listOf(4, 4, 2), result.sides.map { it.placements.size })
        assertEquals(3, result.sheetCount(duplex = false))
        assertEquals(2, result.sheetCount(duplex = true))
        assertEquals(5, plan(List(9) { a4Page }, noMargins).sheetCount(duplex = true))
    }

    @Test
    fun `every supported grid keeps all pages inside the printable area without overlap`() {
        for (count in PagesPerSheet.SUPPORTED) {
            for (orientation in Orientation.entries) {
                val settings = noMargins.copy(pagesPerSheet = PagesPerSheet(count), orientation = orientation, scaling = Scaling.FitToPage)
                val side = plan(List(count) { if (it % 2 == 0) a4Page else landscapePage }, settings).sides.single()
                assertEquals(count, side.placements.size)
                side.placements.forEachIndexed { i, p ->
                    assertTrue(p.bounds.left >= -0.01 && p.bounds.top >= -0.01 && p.bounds.right <= a4.widthPoints + 0.01 && p.bounds.bottom <= a4.heightPoints + 0.01, "$count-up $orientation page $i out of bounds: ${p.bounds}")
                    side.placements.drop(i + 1).forEach { q -> assertTrue(p.bounds.intersect(q.bounds).isEmpty(), "$count-up $orientation overlap") }
                }
            }
        }
    }

    @Test
    fun `bounds always equal the transformed page rectangle`() {
        val sheets = listOf(a4, MediaSize.LETTER, MediaSize.A5.rotated())
        val sources = listOf(a4Page, landscapePage, letterPage, PageGeometry(100.0, 400.0))
        for (sheet in sheets) for (src in sources) for (orientation in Orientation.entries) for (scaling in listOf(Scaling.ShrinkToFit, Scaling.FitToPage, Scaling.Fill, Scaling.ActualSize, Scaling.Custom(37))) for (n in listOf(1, 2, 4)) {
            val settings = PrintSettings(orientation = orientation, scaling = scaling, pagesPerSheet = PagesPerSheet(n), margins = MarginSetting.Custom(Margins.uniform(1000)))
            val side = plan(List(n) { src }, settings, sheet).sides.single()
            side.placements.forEach { p ->
                val mapped = p.transform.mapBounds(Rect(0.0, 0.0, src.widthPoints, src.heightPoints))
                assertNear(mapped.left, p.bounds.left, 1e-6, "left")
                assertNear(mapped.top, p.bounds.top, 1e-6, "top")
                assertNear(mapped.right, p.bounds.right, 1e-6, "right")
                assertNear(mapped.bottom, p.bounds.bottom, 1e-6, "bottom")
                // Scale is uniform: the transform's linear part is scale * a quarter-turn matrix.
                assertNear(p.scale * p.scale, p.transform.a * p.transform.a + p.transform.b * p.transform.b, 1e-9)
                if (scaling != Scaling.Fill && scaling != Scaling.ActualSize && scaling !is Scaling.Custom) {
                    assertTrue(p.bounds.width <= p.clip.width + 0.01 && p.bounds.height <= p.clip.height + 0.01)
                }
            }
        }
    }

    // --- booklet ------------------------------------------------------------------------------

    private fun bookletOrder(result: LayoutPlan): List<List<Int?>> =
        result.sides.map { side -> side.placements.sortedBy { it.bounds.top * 10_000 + it.bounds.left }.let { ps -> ps.map { it.sourcePage } } }

    /** Reads a booklet side as it will be seen when the sheet is held landscape, left to right. */
    private fun landscapeReadingOrder(side: Side): List<Int?> =
        // On a portrait sheet the landscape canvas is turned clockwise: canvas-left is the sheet's top.
        side.placements.sortedBy { it.bounds.top }.map { it.sourcePage }

    @Test
    fun `an eight page booklet is imposed for saddle stitching`() {
        val result = plan(List(8) { a4Page }, noMargins.copy(booklet = true))
        assertTrue(result.requiresDuplex)
        assertEquals(Sides.TWO_SIDED_SHORT_EDGE, result.requiredSides)
        assertEquals(4, result.sides.size)
        assertEquals(
            listOf(listOf(8, 1), listOf(2, 7), listOf(6, 3), listOf(4, 5)),
            result.sides.map { landscapeReadingOrder(it) },
        )
    }

    @Test
    fun `right binding swaps the pages of every spread`() {
        val result = plan(List(8) { a4Page }, noMargins.copy(booklet = true, bookletBinding = BookletBinding.RIGHT))
        assertEquals(
            listOf(listOf(1, 8), listOf(7, 2), listOf(3, 6), listOf(5, 4)),
            result.sides.map { landscapeReadingOrder(it) },
        )
    }

    @Test
    fun `a booklet is padded with blank pages to a multiple of four`() {
        val result = plan(List(6) { a4Page }, noMargins.copy(booklet = true))
        assertTrue(LayoutPlan.Note.BOOKLET_PADDED in result.notes)
        assertEquals(4, result.sides.size) // two sheets
        // Padding positions 7 and 8 are blank, so the outermost front carries only page 1.
        assertEquals(listOf(1), result.sides[0].placements.map { it.sourcePage })
        assertEquals(listOf(2), result.sides[1].placements.map { it.sourcePage })
        assertEquals(setOf(1, 2, 3, 4, 5, 6), result.sides.flatMap { s -> s.placements.map { it.sourcePage } }.toSet())
    }

    @Test
    fun `booklet pages are shrunk to half a sheet and stay on their half`() {
        val result = plan(List(4) { a4Page }, noMargins.copy(booklet = true))
        result.sides.forEach { side ->
            assertEquals(2, side.placements.size)
            side.placements.forEach { p ->
                assertNear(0.70, p.scale, 0.01)
                assertNotNull(p.sourcePage)
                assertTrue(p.bounds.right <= a4.widthPoints + 0.01 && p.bounds.bottom <= a4.heightPoints + 0.01)
            }
        }
    }

    @Test
    fun `booklet on a landscape sheet flips on the long edge`() {
        val result = plan(List(4) { landscapePage }, noMargins.copy(booklet = true), sheet = a4.rotated())
        assertEquals(Sides.TWO_SIDED_LONG_EDGE, result.requiredSides)
    }

    @Test
    fun `booklet of nothing is empty`() {
        val result = plan(emptyList(), noMargins.copy(booklet = true))
        assertTrue(result.isEmpty)
        assertEquals(0, bookletOrder(result).size)
    }
}
