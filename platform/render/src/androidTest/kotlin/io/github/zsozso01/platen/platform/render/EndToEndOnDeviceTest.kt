package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Color
import io.github.zsozso01.platen.backend.raster.PwgRasterBackend
import io.github.zsozso01.platen.core.engine.PrintEngine
import io.github.zsozso01.platen.core.engine.PrintRequest
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.raster.PwgRasterReader
import io.github.zsozso01.platen.route.ipp.IppEndpoint
import io.github.zsozso01.platen.route.ipp.IppJobProtocol
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A real PDF through the real Android renderer, planner, PWG encoder and IPP client into a fake
 * printer running in this process. The received pages are also saved as PNGs under `cache/e2e/`
 * so a person (or `adb pull`) can look at what the printer would have printed.
 */
class EndToEndOnDeviceTest {
    private val printer = FakeIppPrinter(FakePrinterProfile.inkjetRasterOnly)
    private val spool = File(TestDocuments.cacheDir, "spool-e2e").apply { mkdirs() }
    private val out = File(TestDocuments.cacheDir, "e2e").apply { mkdirs() }

    @After
    fun cleanUp() {
        printer.close()
        spool.deleteRecursively()
    }

    private fun print(file: File, settings: PrintSettings, name: String = "e2e"): List<JobEvent> {
        val doc = DocumentOpener(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).openFile(file, name)
        val protocol = IppJobProtocol(
            IppEndpoint(printer.connector(), "127.0.0.1:${printer.port}", printer.path, printer.uri),
            pollIntervalMillis = 10,
        )
        val engine = PrintEngine(mapOf(DocumentFormat.PWG_RASTER to PwgRasterBackend()), spool, { AndroidSideRasterizer(doc) }, Dispatchers.IO)
        return try {
            runBlocking { engine.print(PrintRequest(protocol, doc, settings, jobName = name, awaitReload = { true })).toList() }
        } finally {
            doc.close()
        }
    }

    private fun savePngs(jobIndex: Int, label: String): List<Bitmap> {
        val reader = PwgRasterReader(ByteArrayInputStream(printer.jobs[jobIndex].document))
        val pages = mutableListOf<Bitmap>()
        while (true) {
            val h = reader.nextPage() ?: break
            val full = Bitmap.createBitmap(h.widthPixels, h.heightPixels, Bitmap.Config.ARGB_8888)
            val bpp = h.colorType.bitsPerPixel / 8
            val row = IntArray(h.widthPixels)
            for (y in 0 until h.heightPixels) {
                val line = reader.readLine()!!
                for (x in 0 until h.widthPixels) {
                    row[x] = if (bpp == 3) Color.rgb(line[x * 3].toInt() and 0xFF, line[x * 3 + 1].toInt() and 0xFF, line[x * 3 + 2].toInt() and 0xFF)
                    else Color.rgb(line[x].toInt() and 0xFF, line[x].toInt() and 0xFF, line[x].toInt() and 0xFF)
                }
                full.setPixels(row, 0, h.widthPixels, 0, y, h.widthPixels, 1)
            }
            val small = Bitmap.createScaledBitmap(full, 620, (620.0 * h.heightPixels / h.widthPixels).toInt(), true)
            File(out, "$label-page${pages.size + 1}.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pages += full
        }
        return pages
    }

    @Test
    fun aTwoPagePdfPrintsToTheRasterOnlyInkjetAndTheOutputIsWhatWeExpect() {
        val pdf = TestDocuments.pdf("e2e.pdf", listOf(TestDocuments.A4, TestDocuments.A4_LANDSCAPE), listOf(Color.RED, Color.BLUE))
        val events = print(pdf, PrintSettings(color = ColorMode.COLOR), "Two pages")
        assertEquals(JobEvent.Completed, events.last())

        val job = printer.jobs.single()
        assertEquals("image/pwg-raster", job.documentFormat)
        val pages = savePngs(0, "two-pages")
        assertEquals(2, pages.size)
        assertEquals(2480, pages[0].width)
        assertEquals(3508, pages[0].height)
        // Page 1: portrait, auto margins of the DeskJet-like profile (3 mm sides/top, 12.7 mm bottom) fit-shrink the page.
        fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 70 && Color.blue(c) < 70
        fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 70 && Color.green(c) < 70
        var redPixels = 0
        for (x in 0 until 400) for (y in 0 until 400) if (isRed(pages[0].getPixel(x, y))) redPixels++
        assertTrue("page 1's red marker is near the top-left ($redPixels px)", redPixels > 3000)
        // Page 2 is landscape, turned onto the portrait sheet: its blue marker is near the top-right.
        var bluePixels = 0
        for (x in 2080 until 2480) for (y in 0 until 400) if (isBlue(pages[1].getPixel(x, y))) bluePixels++
        assertTrue("page 2's blue marker is near the top-right ($bluePixels px)", bluePixels > 3000)
    }

    @Test
    fun manualDuplexOnTheInkjetSendsFrontsThenBacksWithRealRendering() {
        val pdf = TestDocuments.pdf("duplex.pdf", List(4) { TestDocuments.A4 }, listOf(Color.RED, Color.BLUE, Color.rgb(0, 160, 0), Color.MAGENTA))
        val events = print(pdf, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = ColorMode.COLOR), "Duplex")
        assertEquals(JobEvent.Completed, events.last())
        assertEquals(1, events.count { it == JobEvent.NeedsReload })
        assertEquals(2, printer.jobs.size)
        val fronts = savePngs(0, "duplex-fronts")
        val backs = savePngs(1, "duplex-backs")
        assertEquals(2, fronts.size)
        assertEquals(2, backs.size)
        fun dominant(bmp: Bitmap): String {
            var r = 0; var b = 0; var g = 0; var m = 0
            for (x in 0 until 400 step 2) for (y in 0 until 400 step 2) {
                val c = bmp.getPixel(x, y)
                if (Color.red(c) > 200 && Color.green(c) < 70 && Color.blue(c) < 70) r++
                if (Color.blue(c) > 200 && Color.red(c) < 70 && Color.green(c) < 70) b++
                if (Color.green(c) in 120..200 && Color.red(c) < 70 && Color.blue(c) < 70) g++
                if (Color.red(c) > 200 && Color.blue(c) > 200 && Color.green(c) < 70) m++
            }
            return listOf("red" to r, "blue" to b, "green" to g, "magenta" to m).maxBy { it.second }.first
        }
        // Fronts are pages 1 and 3 in order; backs are pages 4 then 2 (reversed for the reload).
        assertEquals(listOf("red", "green"), fronts.map(::dominant))
        // Backs are printed last to first, and (long edge, default options) upright: page 4 magenta, page 2 blue.
        assertEquals(listOf("magenta", "blue"), backs.map(::dominant))
    }
}
