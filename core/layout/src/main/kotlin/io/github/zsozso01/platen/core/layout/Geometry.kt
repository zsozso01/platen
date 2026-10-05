package io.github.zsozso01.platen.core.layout

import kotlin.math.max
import kotlin.math.min

/**
 * A source page's size in PostScript points (1/72 inch), already including any `/Rotate` the document
 * applies, so that `width x height` is how the page looks when viewed.
 */
public data class PageGeometry(val widthPoints: Double, val heightPoints: Double) {
    init {
        require(widthPoints > 0 && heightPoints > 0) { "Page size must be positive" }
    }

    public val isLandscape: Boolean get() = widthPoints > heightPoints
}

/** An axis-aligned rectangle. Coordinates are in points with the origin top-left and y growing downwards. */
public data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    public val width: Double get() = right - left
    public val height: Double get() = bottom - top
    public val centerX: Double get() = (left + right) / 2
    public val centerY: Double get() = (top + bottom) / 2

    public fun inset(l: Double, t: Double, r: Double, b: Double): Rect = Rect(left + l, top + t, right - r, bottom - b)

    public fun isEmpty(): Boolean = width <= 0.0 || height <= 0.0

    public fun intersect(o: Rect): Rect = Rect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))
}

/**
 * A 2-D affine transform: `x' = a*x + c*y + e`, `y' = b*x + d*y + f`. Maps source-page coordinates
 * (points, origin top-left, y down) to sheet coordinates in the same convention. The renderer applies
 * it directly (an Android `Matrix`, or a PDF `cm` operator after flipping y).
 */
public data class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    public fun mapX(x: Double, y: Double): Double = a * x + c * y + e

    public fun mapY(x: Double, y: Double): Double = b * x + d * y + f

    /** `this` applied after [first]: `this(first(p))`. */
    public fun after(first: Affine): Affine = Affine(
        a = a * first.a + c * first.b,
        b = b * first.a + d * first.b,
        c = a * first.c + c * first.d,
        d = b * first.c + d * first.d,
        e = a * first.e + c * first.f + e,
        f = b * first.e + d * first.f + f,
    )

    /** The bounding box of [rect] after this transform. */
    public fun mapBounds(rect: Rect): Rect {
        val xs = doubleArrayOf(mapX(rect.left, rect.top), mapX(rect.right, rect.top), mapX(rect.left, rect.bottom), mapX(rect.right, rect.bottom))
        val ys = doubleArrayOf(mapY(rect.left, rect.top), mapY(rect.right, rect.top), mapY(rect.left, rect.bottom), mapY(rect.right, rect.bottom))
        return Rect(xs.min(), ys.min(), xs.max(), ys.max())
    }

    public companion object {
        public val IDENTITY: Affine = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** Mirrors the sheet left to right: `x' = width - x`. */
        public fun mirrorX(sheetWidth: Double): Affine = Affine(-1.0, 0.0, 0.0, 1.0, sheetWidth, 0.0)

        /** Mirrors the sheet top to bottom: `y' = height - y`. */
        public fun mirrorY(sheetHeight: Double): Affine = Affine(1.0, 0.0, 0.0, -1.0, 0.0, sheetHeight)

        /** Turns the sheet half a turn about its centre. */
        public fun rotate180(sheetWidth: Double, sheetHeight: Double): Affine = Affine(-1.0, 0.0, 0.0, -1.0, sheetWidth, sheetHeight)

        /**
         * Places a page of size [page], uniformly scaled by [scale] and turned clockwise by
         * [rotationDegrees] (0, 90, 180 or 270), so that it is centred in [into].
         */
        public fun placeCentred(page: PageGeometry, scale: Double, rotationDegrees: Int, into: Rect): Affine {
            val w = page.widthPoints
            val h = page.heightPoints
            // Rotation about the page centre, then scale, then move the centre to the cell centre.
            val (ra, rb, rc, rd) = when (rotationDegrees) {
                0 -> listOf(1.0, 0.0, 0.0, 1.0)
                90 -> listOf(0.0, 1.0, -1.0, 0.0) // clockwise on a y-down plane
                180 -> listOf(-1.0, 0.0, 0.0, -1.0)
                270 -> listOf(0.0, -1.0, 1.0, 0.0)
                else -> throw IllegalArgumentException("Rotation must be a multiple of 90 degrees")
            }
            val a = ra * scale
            val b = rb * scale
            val c = rc * scale
            val d = rd * scale
            val cx = w / 2
            val cy = h / 2
            val e = into.centerX - (a * cx + c * cy)
            val f = into.centerY - (b * cx + d * cy)
            return Affine(a, b, c, d, e, f)
        }
    }
}
