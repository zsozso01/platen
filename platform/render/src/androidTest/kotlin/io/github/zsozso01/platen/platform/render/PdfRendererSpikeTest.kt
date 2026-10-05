package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Settles what the platform renderer can do, because the layout planner relies on it: a rotated matrix,
 * a clip, and rendering a region of a page into a small bitmap.
 */
class PdfRendererSpikeTest {
    /** A 200 x 100 pt page: a red 40 x 30 box at the top-left, blue elsewhere nothing, white background. */
    private fun makePdf(): File {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "spike.pdf")
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(200, 100, 1).create())
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawRect(0f, 0f, 40f, 30f, Paint().apply { color = Color.RED })
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun <T> withPage(block: (PdfRenderer.Page) -> T): T {
        val pfd = ParcelFileDescriptor.open(makePdf(), ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            renderer.openPage(0).use { return block(it) }
        }
    }

    private fun newBitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun Bitmap.isRed(x: Int, y: Int) = Color.red(getPixel(x, y)) > 200 && Color.green(getPixel(x, y)) < 60

    @Test
    fun pageSizeIsReportedInPoints() = withPage { page ->
        assertEquals(200, page.width)
        assertEquals(100, page.height)
    }

    @Test
    fun scaleAndTranslateWork() = withPage { page ->
        val bmp = newBitmap(400, 200)
        page.render(bmp, null, Matrix().apply { setScale(2f, 2f) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        assert(bmp.isRed(10, 10)) { "scaled box missing" }
        assert(!bmp.isRed(100, 10)) { "box too large" }
        assert(bmp.isRed(75, 55)) { "scaled box should reach (80, 60)" }
        assert(!bmp.isRed(90, 70))
    }

    @Test
    fun aQuarterTurnMatrixIsHonoured() = withPage { page ->
        // Rotate 90 degrees clockwise about the origin, then shift into view: page (x, y) -> bitmap (100 - y, x).
        val bmp = newBitmap(100, 200)
        val m = Matrix().apply { setValues(floatArrayOf(0f, -1f, 100f, 1f, 0f, 0f, 0f, 0f, 1f)) }
        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        // The red box was at the page's top-left; after a clockwise quarter turn it is at the bitmap's top-right.
        val topRight = bmp.isRed(95, 10)
        val topLeft = bmp.isRed(5, 10)
        println("SPIKE rotation: topRight=$topRight topLeft=$topLeft")
        assert(topRight && !topLeft) { "rotation not honoured: topRight=$topRight topLeft=$topLeft" }
    }

    @Test
    fun aClipRestrictsDrawingToTheRectangleWhenTheTransformIsExplicit() = withPage { page ->
        val bmp = newBitmap(200, 100)
        page.render(bmp, Rect(0, 0, 20, 100), Matrix(), PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        assert(bmp.isRed(10, 10)) { "inside the clip" }
        assert(!bmp.isRed(30, 10)) { "outside the clip must stay untouched" }
    }

    @Test
    fun withoutATransformThePageIsScaledToTheClipNotCroppedByIt() = withPage { page ->
        // Documented platform behaviour the rasteriser must avoid: a null matrix fits the page into the clip.
        val bmp = newBitmap(200, 100)
        page.render(bmp, Rect(0, 0, 20, 100), null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        assert(bmp.isRed(2, 10)) { "the whole page is squeezed into 20 px, so the box is about 4 px wide" }
        assert(!bmp.isRed(10, 10))
    }

    @Test
    fun aBandOfAPageCanBeRenderedWithANegativeTranslation() = withPage { page ->
        // Render only rows 10..40 of the page into a 200 x 30 bitmap.
        val bmp = newBitmap(200, 30)
        page.render(bmp, null, Matrix().apply { setTranslate(0f, -10f) }, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        assert(bmp.isRed(10, 5)) { "box rows 10..30 are inside this band" }
        assert(!bmp.isRed(10, 25)) { "box ends at page row 30, band row 20" }
    }
}
