package io.github.zsozso01.platen.job

import android.graphics.Bitmap
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.layout.LayoutPlan
import io.github.zsozso01.platen.platform.render.AndroidSideRasterizer

/** Renders one laid-out side to a small bitmap, for the preview and for the picture of a page being printed. Blocking. */
fun renderSideBitmap(document: RefCountedDocument, layout: LayoutPlan, sideIndex: Int, dpi: Int): Bitmap? = runCatching {
    val rasterizer = AndroidSideRasterizer(document, bandRows = 128)
    try {
        val side = rasterizer.rasterize(layout.sides[sideIndex], layout.sheet, dpi, RasterPixelFormat.RGB24)
        val width = side.widthPx
        val height = side.heightPx
        val rows = ByteArray(width * height * 3)
        side.readRows(0, height, rows)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            pixels[i] = 0xFF shl 24 or ((rows[i * 3].toInt() and 0xFF) shl 16) or ((rows[i * 3 + 1].toInt() and 0xFF) shl 8) or (rows[i * 3 + 2].toInt() and 0xFF)
        }
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    } finally {
        rasterizer.close()
    }
}.getOrNull()
