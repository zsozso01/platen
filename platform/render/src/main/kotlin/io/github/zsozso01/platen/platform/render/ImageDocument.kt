package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import io.github.zsozso01.platen.core.engine.PdfFile
import io.github.zsozso01.platen.core.layout.PageGeometry
import java.io.File
import java.io.IOException

/**
 * A single image (photo, screenshot) as a one-page document. Pixels are interpreted at
 * [ASSUMED_DPI], so a typical photo is larger than the sheet and is shrunk to fit; the UI defaults
 * images to "fit to page" so small ones are enlarged too. The EXIF orientation is honoured.
 */
public class ImageDocument private constructor(
    private val file: File,
    override val name: String,
    private val widthPx: Int,
    private val heightPx: Int,
    private val exifOrientation: Int,
    private val deleteOnClose: Boolean,
) : RenderableDocument {
    override val pageCount: Int = 1
    override val pdf: PdfFile? = null

    private val quarterTurned = exifOrientation in setOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE)
    private val pageWidth = (if (quarterTurned) heightPx else widthPx) * 72.0 / ASSUMED_DPI
    private val pageHeight = (if (quarterTurned) widthPx else heightPx) * 72.0 / ASSUMED_DPI

    private var decoded: Bitmap? = null

    override fun pageGeometry(pageIndex: Int): PageGeometry = PageGeometry(pageWidth, pageHeight)

    @Synchronized
    override fun drawPage(pageIndex: Int, target: Bitmap, matrix: Matrix, clip: Rect) {
        val bitmap = decoded ?: decode().also { decoded = it }
        // decoded pixel -> page points: undo the downsampling, apply the EXIF turn, scale to points.
        val toPage = Matrix().apply {
            postScale(widthPx.toFloat() / bitmap.width, heightPx.toFloat() / bitmap.height) // back to full-resolution pixels
            postConcat(exifMatrix())
            postScale((72.0 / ASSUMED_DPI).toFloat(), (72.0 / ASSUMED_DPI).toFloat())
        }
        val canvas = Canvas(target)
        canvas.save()
        canvas.clipRect(clip)
        canvas.concat(matrix)
        canvas.concat(toPage)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
    }

    @Synchronized
    override fun close() {
        decoded?.recycle()
        decoded = null
        if (deleteOnClose) file.delete()
    }

    private fun decode(): Bitmap {
        var sample = 1
        while ((widthPx / sample).toLong() * (heightPx / sample) > MAX_DECODED_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.path, options) ?: throw IOException("Could not decode $name")
    }

    /** The matrix that turns a full-resolution image upright: maps stored pixel coordinates to upright pixel coordinates. */
    private fun exifMatrix(): Matrix = exifMatrix(exifOrientation, widthPx, heightPx)

    public companion object {
        /**
         * EXIF orientation 1 to 8 as a matrix over a stored image of [w] x [h] pixels, mapping stored pixel
         * coordinates to upright ones (`x' = a*x + c*y + e`, `y' = b*x + d*y + f`; the upright image is
         * h x w for the quarter-turn cases).
         */
        internal fun exifMatrix(orientation: Int, w: Int, h: Int): Matrix {
            val width = w.toFloat()
            val height = h.toFloat()
            //                                     a     c     e       b     d     f
            val v = when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(-1f, 0f, width, 0f, 1f, 0f)
                ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(-1f, 0f, width, 0f, -1f, height)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(1f, 0f, 0f, 0f, -1f, height)
                ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f)
                ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(0f, -1f, height, 1f, 0f, 0f)
                ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(0f, -1f, height, -1f, 0f, width)
                ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, width)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
            }
            return Matrix().apply { setValues(floatArrayOf(v[0], v[1], v[2], v[3], v[4], v[5], 0f, 0f, 1f)) }
        }

        /** Pixels per inch assumed for images, which carry no reliable physical size. */
        public const val ASSUMED_DPI: Int = 150

        /** Decoding stops short of this many pixels; larger photos are downsampled. 16 MP is 64 MB as ARGB_8888. */
        private const val MAX_DECODED_PIXELS = 16L * 1024 * 1024

        /** Returns null if [file] is not an image Android can decode. */
        @Throws(DocumentOpenException::class)
        public fun openOrNull(file: File, name: String, deleteOnClose: Boolean = false): ImageDocument? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outMimeType == null) return null
            val orientation = try {
                file.inputStream().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            } catch (_: IOException) {
                ExifInterface.ORIENTATION_NORMAL
            }
            return ImageDocument(file, name, bounds.outWidth, bounds.outHeight, orientation, deleteOnClose)
        }
    }
}
