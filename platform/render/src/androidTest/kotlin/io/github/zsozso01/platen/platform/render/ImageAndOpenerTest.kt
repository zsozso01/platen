package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.layout.LayoutPlanner
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Scaling
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ImageAndOpenerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val toClose = mutableListOf<RenderableDocument>()

    @After
    fun cleanUp() = toClose.forEach { it.close() }

    /** A 200 x 100 stored image: white, with a red 50 x 50 square at the stored top-left. */
    private fun jpeg(name: String, orientation: Int): File {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 0 until 50) for (y in 0 until 50) setPixel(x, y, Color.RED)
        }
        val file = File(context.cacheDir, name)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // PNG carries no EXIF through ExifInterface on all versions: store as JPEG for the orientation cases.
        val jpg = File(context.cacheDir, "$name.jpg")
        jpg.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(jpg.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        file.delete()
        return jpg
    }

    private fun renderOnA4(doc: RenderableDocument, dpi: Int = 50): Triple<Int, Int, ByteArray> {
        val plan = LayoutPlanner.plan(List(doc.pageCount) { doc.pageGeometry(it) }, PrintSettings(margins = MarginSetting.None, scaling = Scaling.FitToPage), MediaSize.A4)
        val rasterizer = AndroidSideRasterizer(doc, 64)
        val side = rasterizer.rasterize(plan.sides[0], MediaSize.A4, dpi, RasterPixelFormat.RGB24)
        val bytes = ByteArray(side.widthPx * side.heightPx * 3)
        side.readRows(0, side.heightPx, bytes)
        rasterizer.close()
        return Triple(side.widthPx, side.heightPx, bytes)
    }

    /** Which quadrant of the sheet the red marker's centre is in, e.g. "top-right"; null if there is no red at all. */
    private fun markerQuadrant(img: Triple<Int, Int, ByteArray>): String? {
        var sx = 0L; var sy = 0L; var n = 0
        val b = img.third
        for (y in 0 until img.second) for (x in 0 until img.first) {
            val o = (y * img.first + x) * 3
            if ((b[o].toInt() and 0xFF) > 200 && (b[o + 1].toInt() and 0xFF) < 70 && (b[o + 2].toInt() and 0xFF) < 70) { sx += x; sy += y; n++ }
        }
        if (n < 20) return null
        val vertical = if (sy / n < img.second / 2) "top" else "bottom"
        val horizontal = if (sx / n < img.first / 2) "left" else "right"
        return "$vertical-$horizontal"
    }

    @Test
    fun anUprightLandscapeImageGetsTurnedOntoThePortraitSheetAndFitsIt() {
        val doc = ImageDocument.openOrNull(jpeg("up", ExifInterface.ORIENTATION_NORMAL), "up.jpg")!!.also { toClose += it }
        assertEquals(96.0, doc.pageGeometry(0).widthPoints, 0.1) // 200 px at 150 dpi
        val img = renderOnA4(doc)
        // The image is landscape (2:1) and the sheet portrait, so auto orientation turns it a quarter turn clockwise;
        // the red square (stored top-left) is therefore at the sheet's top-right.
        assertEquals("top-right", markerQuadrant(img))
    }

    @Test
    fun exifOrientationSixMeansRotateClockwise() {
        // Stored landscape with red at the top-left, EXIF 6: displayed upright it is portrait with red at the TOP-RIGHT.
        val doc = ImageDocument.openOrNull(jpeg("rot6", ExifInterface.ORIENTATION_ROTATE_90), "rot6.jpg")!!.also { toClose += it }
        assertEquals(48.0, doc.pageGeometry(0).widthPoints, 0.1) // now 100 px wide
        assertEquals(96.0, doc.pageGeometry(0).heightPoints, 0.1)
        val img = renderOnA4(doc)
        // Portrait image on a portrait sheet: no auto-turn; the red square is at the top-right.
        assertEquals("top-right", markerQuadrant(img))
    }

    @Test
    fun exifOrientationEightMeansRotateCounterClockwise() {
        val doc = ImageDocument.openOrNull(jpeg("rot8", ExifInterface.ORIENTATION_ROTATE_270), "rot8.jpg")!!.also { toClose += it }
        val img = renderOnA4(doc)
        // 270 clockwise: the stored top-left ends up at the bottom-left of the upright (portrait) image.
        assertEquals("bottom-left", markerQuadrant(img))
    }

    @Test
    fun exifOrientationThreeMeansUpsideDown() {
        val doc = ImageDocument.openOrNull(jpeg("rot3", ExifInterface.ORIENTATION_ROTATE_180), "rot3.jpg")!!.also { toClose += it }
        val img = renderOnA4(doc)
        // Landscape stays landscape; 180 degrees puts the stored top-left at the bottom-right of the upright image;
        // turned onto the portrait sheet that is the sheet's bottom-left.
        assertEquals("bottom-left", markerQuadrant(img))
    }

    @Test
    fun aFileThatIsNotAnImageIsNotOne() {
        val file = File(context.cacheDir, "not-an-image.txt").apply { writeText("hello") }
        assertNull(ImageDocument.openOrNull(file, "x"))
        file.delete()
    }

    // --- opener -----------------------------------------------------------------------------------

    private fun opener() = DocumentOpener(context)

    @Test
    fun theOpenerRecognisesPdfsAndImagesByContentNotByName() {
        val pdf = TestDocuments.pdf("misnamed.png", listOf(TestDocuments.A4))
        val doc = opener().open(Uri.fromFile(pdf)).also { toClose += it }
        assertTrue(doc is PdfRendererDocument)
        assertNotNull(doc.pdf)
        val image = jpeg("pic", ExifInterface.ORIENTATION_NORMAL)
        val imageDoc = opener().open(Uri.fromFile(image)).also { toClose += it }
        assertTrue(imageDoc is ImageDocument)
        assertNull(imageDoc.pdf)
    }

    @Test
    fun aTextFileIsRefusedWithAHelpfulReason() {
        val file = File(context.cacheDir, "letter.txt").apply { writeText("Dear printer,") }
        try {
            opener().open(Uri.fromFile(file))
            fail("a text file should not open")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.UNSUPPORTED_TYPE, e.reason)
            assertTrue(e.message!!.contains("PDF"))
        }
        val leftovers = File(context.cacheDir, "documents").listFiles().orEmpty()
        assertTrue("no copy may be left behind: ${leftovers.map { it.name }}", leftovers.isEmpty() || leftovers.all { !it.isFile || it.length() != 13L })
    }

    @Test
    fun aTruncatedPdfIsReportedAsCorruptNotACrash() {
        val good = TestDocuments.pdf("full.pdf", listOf(TestDocuments.A4))
        val broken = File(context.cacheDir, "broken.pdf").apply { writeBytes(good.readBytes().copyOf(good.length().toInt() / 3)) }
        try {
            opener().open(Uri.fromFile(broken)).also { toClose += it }
            fail("a truncated PDF should not open")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.CORRUPT, e.reason)
        }
    }

    @Test
    fun aMissingFileIsUnreadable() {
        try {
            opener().open(Uri.fromFile(File(context.cacheDir, "does-not-exist.pdf")))
            fail("a missing file should not open")
        } catch (e: DocumentOpenException) {
            assertEquals(DocumentOpenException.Reason.UNREADABLE, e.reason)
        }
    }

    @Test
    fun closingTheDocumentDeletesItsCacheCopy() {
        val doc = opener().open(Uri.fromFile(TestDocuments.pdf("tidy.pdf", listOf(TestDocuments.A4))))
        val before = File(context.cacheDir, "documents").listFiles().orEmpty().size
        doc.close()
        val after = File(context.cacheDir, "documents").listFiles().orEmpty().size
        assertEquals(before - 1, after)
    }
}
