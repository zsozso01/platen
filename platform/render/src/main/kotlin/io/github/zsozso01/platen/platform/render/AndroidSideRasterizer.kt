package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.engine.RasterSide
import io.github.zsozso01.platen.core.engine.SideRasterizer
import io.github.zsozso01.platen.core.layout.Placement
import io.github.zsozso01.platen.core.layout.Side
import io.github.zsozso01.platen.core.model.MediaSize
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Renders laid-out faces with Android's own PDF and image stack. A face is produced in horizontal
 * bands: each band is drawn into one reusable ARGB bitmap through every placement's matrix and clip,
 * then converted to grey or RGB rows. Memory is one band (about 2.5 MB for A4 at 300 dpi), however
 * large the page.
 */
public class AndroidSideRasterizer(
    private val document: RenderableDocument,
    private val bandRows: Int = DEFAULT_BAND_ROWS,
) : SideRasterizer {
    private var band: Bitmap? = null
    private var pixels: IntArray? = null

    override fun rasterize(side: Side, sheet: MediaSize, dpi: Int, format: RasterPixelFormat): RasterSide {
        val width = sheet.widthPixels(dpi)
        val height = sheet.heightPixels(dpi)
        val scale = dpi / 72.0
        return object : RasterSide {
            override val widthPx: Int = width
            override val heightPx: Int = height
            override val format: RasterPixelFormat = format

            override fun readRows(startRow: Int, count: Int, into: ByteArray) {
                var done = 0
                while (done < count) {
                    val n = minOf(bandRows, count - done)
                    renderBand(side, scale, width, startRow + done, n, format, into, done * width * format.bytesPerPixel)
                    done += n
                }
            }

            override fun close() {}
        }
    }

    private fun renderBand(side: Side, scale: Double, width: Int, startRow: Int, rows: Int, format: RasterPixelFormat, out: ByteArray, outOffset: Int) {
        val bitmap = band?.takeIf { it.width == width } ?: Bitmap.createBitmap(width, bandRows, Bitmap.Config.ARGB_8888).also {
            band?.recycle()
            band = it
            pixels = IntArray(width * bandRows)
        }
        bitmap.eraseColor(Color.WHITE)
        val bounds = Rect(0, 0, width, rows)
        for (placement in side.placements) {
            val page = placement.sourcePage ?: continue
            val clip = clipPixels(placement, scale, startRow).apply { if (!intersect(bounds)) continue }
            document.drawPage(page - 1, bitmap, pixelMatrix(placement, scale, startRow), clip)
            if (placement.drawBorder) drawBorder(bitmap, placement, scale, startRow)
        }
        val ints = pixels!!
        bitmap.getPixels(ints, 0, width, 0, 0, width, rows)
        convert(ints, width * rows, format, out, outOffset)
    }

    /** page points -> sheet points (the placement) -> pixels -> band-relative pixels. */
    private fun pixelMatrix(p: Placement, scale: Double, startRow: Int): Matrix {
        val t = p.transform
        val s = scale
        // Android's Matrix values are row-major: [a c e / b d f / 0 0 1]. Fold in the scale and the band offset.
        val values = floatArrayOf(
            (t.a * s).toFloat(), (t.c * s).toFloat(), (t.e * s).toFloat(),
            (t.b * s).toFloat(), (t.d * s).toFloat(), (t.f * s - startRow).toFloat(),
            0f, 0f, 1f,
        )
        return Matrix().apply { setValues(values) }
    }

    private fun clipPixels(p: Placement, scale: Double, startRow: Int) = Rect(
        floor(p.clip.left * scale).toInt(),
        floor(p.clip.top * scale - startRow).toInt(),
        ceil(p.clip.right * scale).toInt(),
        ceil(p.clip.bottom * scale - startRow).toInt(),
    )

    private fun drawBorder(bitmap: Bitmap, p: Placement, scale: Double, startRow: Int) {
        val paint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.BLACK
            strokeWidth = maxOf(1.0, scale / 72.0 * 0.5).toFloat()
        }
        Canvas(bitmap).drawRect(
            (p.bounds.left * scale).toFloat(),
            (p.bounds.top * scale - startRow).toFloat(),
            (p.bounds.right * scale).toFloat(),
            (p.bounds.bottom * scale - startRow).toFloat(),
            paint,
        )
    }

    private fun convert(ints: IntArray, count: Int, format: RasterPixelFormat, out: ByteArray, offset: Int) {
        var o = offset
        when (format) {
            RasterPixelFormat.GRAY8 -> for (i in 0 until count) {
                val c = ints[i]
                out[o++] = ((77 * ((c shr 16) and 0xFF) + 151 * ((c shr 8) and 0xFF) + 28 * (c and 0xFF)) shr 8).toByte()
            }
            RasterPixelFormat.RGB24 -> for (i in 0 until count) {
                val c = ints[i]
                out[o++] = (c shr 16).toByte()
                out[o++] = (c shr 8).toByte()
                out[o++] = c.toByte()
            }
        }
    }

    override fun close() {
        band?.recycle()
        band = null
        pixels = null
    }

    public companion object {
        /** 256 rows of an A4 page at 300 dpi is 2.5 MB as ARGB_8888. */
        public const val DEFAULT_BAND_ROWS: Int = 256
    }
}
