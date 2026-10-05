package io.github.zsozso01.platen.testing.support

import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.engine.RasterSide
import io.github.zsozso01.platen.core.engine.SideRasterizer
import io.github.zsozso01.platen.core.layout.Placement
import io.github.zsozso01.platen.core.layout.Rect
import io.github.zsozso01.platen.core.layout.Side
import io.github.zsozso01.platen.core.model.MediaSize
import kotlin.math.roundToInt

/**
 * A fake rasteriser for tests. Each placed page becomes a solid block of grey whose level encodes the
 * page number (see [shade]), clipped to its cell, with a black marker where the page's own top-left
 * corner lands. Tests read pixels back to prove scaling, rotation, mirroring and ordering.
 */
class SolidBlockRasterizer : SideRasterizer {
    var rasterizeCalls = 0
        private set

    override fun rasterize(side: Side, sheet: MediaSize, dpi: Int, format: RasterPixelFormat): RasterSide {
        rasterizeCalls++
        val w = sheet.widthPixels(dpi)
        val h = sheet.heightPixels(dpi)
        val scale = dpi / 72.0
        fun px(rect: Rect) = intArrayOf(
            (rect.left * scale).roundToInt().coerceIn(0, w),
            (rect.top * scale).roundToInt().coerceIn(0, h),
            (rect.right * scale).roundToInt().coerceIn(0, w),
            (rect.bottom * scale).roundToInt().coerceIn(0, h),
        )
        val blocks = side.placements.map { p ->
            val clip = px(p.clip)
            val page = px(p.bounds)
            val area = intArrayOf(maxOf(clip[0], page[0]), maxOf(clip[1], page[1]), minOf(clip[2], page[2]), minOf(clip[3], page[3]))
            Block(area, shade(p.sourcePage), markerOf(p, scale, w, h))
        }
        return object : RasterSide {
            override val widthPx = w
            override val heightPx = h
            override val format = format
            override fun readRows(startRow: Int, count: Int, into: ByteArray) {
                val bpp = format.bytesPerPixel
                java.util.Arrays.fill(into, 0, count * w * bpp, 0xFF.toByte())
                for (r in 0 until count) {
                    val y = startRow + r
                    for (b in blocks) {
                        if (y !in b.area[1] until b.area[3]) continue
                        paint(into, (r * w + b.area[0]) * bpp, (b.area[2] - b.area[0]) * bpp, bpp, b.shade)
                        val m = b.marker
                        if (m != null && y in m[1] until m[3]) paint(into, (r * w + m[0]) * bpp, (m[2] - m[0]) * bpp, bpp, MARKER)
                    }
                }
            }

            override fun close() {}
        }
    }

    override fun close() {}

    private class Block(val area: IntArray, val shade: Int, val marker: IntArray?)

    /** The source page's top-left 12 x 12 point corner, mapped onto the sheet, clipped to the area. */
    private fun markerOf(p: Placement, scale: Double, w: Int, h: Int): IntArray? {
        if (p.sourcePage == null) return null
        val b = p.transform.mapBounds(Rect(0.0, 0.0, 12.0, 12.0))
        return intArrayOf(
            (b.left * scale).roundToInt().coerceIn(0, w),
            (b.top * scale).roundToInt().coerceIn(0, h),
            (b.right * scale).roundToInt().coerceIn(0, w),
            (b.bottom * scale).roundToInt().coerceIn(0, h),
        )
    }

    private fun paint(into: ByteArray, offset: Int, length: Int, bpp: Int, value: Int) {
        if (length <= 0) return
        java.util.Arrays.fill(into, offset, offset + length, value.toByte())
    }

    companion object {
        const val MARKER = 0

        /** Grey level for a page: page 1 = 235, page 2 = 220, ... never below 40 and never the marker. */
        fun shade(page: Int?): Int = if (page == null) 255 else maxOf(40, 250 - 15 * page)
    }
}
