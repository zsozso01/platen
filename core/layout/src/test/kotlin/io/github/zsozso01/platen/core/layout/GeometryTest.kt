package io.github.zsozso01.platen.core.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryTest {
    private fun near(a: Double, b: Double) = assertTrue(kotlin.math.abs(a - b) < 1e-9, "$a vs $b")

    @Test
    fun `placing centres the page in the cell`() {
        val page = PageGeometry(100.0, 200.0)
        val cell = Rect(0.0, 0.0, 400.0, 400.0)
        val t = Affine.placeCentred(page, 1.0, 0, cell)
        near(150.0, t.mapX(0.0, 0.0))
        near(100.0, t.mapY(0.0, 0.0))
        near(250.0, t.mapX(100.0, 200.0))
        near(300.0, t.mapY(100.0, 200.0))
    }

    @Test
    fun `a clockwise quarter turn moves the top-left corner to the top-right`() {
        val page = PageGeometry(100.0, 200.0)
        val cell = Rect(0.0, 0.0, 200.0, 100.0)
        val t = Affine.placeCentred(page, 1.0, 90, cell)
        near(200.0, t.mapX(0.0, 0.0))
        near(0.0, t.mapY(0.0, 0.0))
        near(200.0, t.mapX(100.0, 0.0)) // top-right -> bottom-right: x stays, y grows
        near(100.0, t.mapY(100.0, 0.0))
    }

    @Test
    fun `half turn and three quarter turn`() {
        val page = PageGeometry(100.0, 100.0)
        val cell = Rect(0.0, 0.0, 100.0, 100.0)
        val half = Affine.placeCentred(page, 1.0, 180, cell)
        near(100.0, half.mapX(0.0, 0.0))
        near(100.0, half.mapY(0.0, 0.0))
        val three = Affine.placeCentred(page, 1.0, 270, cell)
        near(0.0, three.mapX(0.0, 0.0))
        near(100.0, three.mapY(0.0, 0.0))
        assertFailsWith<IllegalArgumentException> { Affine.placeCentred(page, 1.0, 45, cell) }
    }

    @Test
    fun `composition applies the first transform first`() {
        val scale = Affine(2.0, 0.0, 0.0, 2.0, 0.0, 0.0)
        val shift = Affine(1.0, 0.0, 0.0, 1.0, 10.0, 5.0)
        val both = shift.after(scale)
        near(12.0, both.mapX(1.0, 1.0))
        near(7.0, both.mapY(1.0, 1.0))
        near(1.0, Affine.IDENTITY.after(Affine.IDENTITY).a)
    }

    @Test
    fun `rect basics`() {
        val r = Rect(10.0, 20.0, 110.0, 70.0)
        assertEquals(100.0, r.width)
        assertEquals(50.0, r.height)
        assertTrue(r.intersect(Rect(200.0, 0.0, 300.0, 10.0)).isEmpty())
        assertEquals(Rect(15.0, 25.0, 105.0, 65.0), r.inset(5.0, 5.0, 5.0, 5.0))
        assertFailsWith<IllegalArgumentException> { PageGeometry(0.0, 5.0) }
    }
}

class SideTransformTest {
    private fun near(a: Double, b: Double) = assertTrue(kotlin.math.abs(a - b) < 1e-9, "$a vs $b")

    @Test
    fun `mirrors and half turn move the corners where expected`() {
        near(100.0, Affine.mirrorX(100.0).mapX(0.0, 30.0))
        near(30.0, Affine.mirrorX(100.0).mapY(0.0, 30.0))
        near(200.0, Affine.mirrorY(200.0).mapY(20.0, 0.0))
        val half = Affine.rotate180(100.0, 200.0)
        near(100.0, half.mapX(0.0, 0.0))
        near(200.0, half.mapY(0.0, 0.0))
        near(0.0, half.mapX(100.0, 200.0))
    }

    @Test
    fun `transforming a side keeps transform, clip and bounds consistent`() {
        val page = PageGeometry(100.0, 100.0)
        val cell = Rect(0.0, 0.0, 100.0, 100.0) // upper half of a 100 x 200 sheet
        val t = Affine.placeCentred(page, 1.0, 0, cell)
        val placement = Placement(1, t, cell, t.mapBounds(Rect(0.0, 0.0, 100.0, 100.0)), false, 1.0)
        near(0.0, placement.bounds.top)

        val turned = Side(listOf(placement)).transformedBy(Affine.rotate180(100.0, 200.0)).placements.single()
        // Half a turn moves the page from the upper half of the sheet to the lower half.
        near(100.0, turned.bounds.top)
        near(200.0, turned.bounds.bottom)
        near(100.0, turned.clip.top)
        // The page's own origin, top-left, now lands at the sheet's bottom-right of that cell.
        near(100.0, turned.transform.mapX(0.0, 0.0))
        near(200.0, turned.transform.mapY(0.0, 0.0))
        val mapped = turned.transform.mapBounds(Rect(0.0, 0.0, 100.0, 100.0))
        near(mapped.top, turned.bounds.top)
        near(mapped.bottom, turned.bounds.bottom)

        val mirrored = Side(listOf(placement)).transformedBy(Affine.mirrorY(200.0)).placements.single()
        near(100.0, mirrored.bounds.top)
        near(200.0, mirrored.bounds.bottom)
        // Mirrored top to bottom, the page's top-left corner is now at the *bottom*-left.
        near(0.0, mirrored.transform.mapX(0.0, 0.0))
        near(200.0, mirrored.transform.mapY(0.0, 0.0))
    }
}
