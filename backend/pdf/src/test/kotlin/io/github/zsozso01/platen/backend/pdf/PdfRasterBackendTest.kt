package io.github.zsozso01.platen.backend.pdf

import io.github.zsozso01.platen.core.engine.BackendJob
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.PrintPlanner
import io.github.zsozso01.platen.core.engine.RasterFaces
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.testing.support.SolidBlockRasterizer
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import io.github.zsozso01.platen.testing.support.laserCaps
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfRasterBackendTest {
    private val pdfOnly = laserCaps.copy(formats = listOf(DocumentFormat.PDF), resolutionsDpi = listOf(50), raster = null, unprintableMargins = null)
    private val colour = pdfOnly.copy(colorModes = setOf(ColorMode.AUTO, ColorMode.COLOR, ColorMode.MONOCHROME))

    // --- a deliberately small PDF reader: enough to check what the writer promised -------------------------

    private class Image(val width: Int, val height: Int, val colorSpace: String, val pixels: ByteArray)

    private class Page(val mediaBox: List<Double>, val image: Image?, val content: String)

    private class Parsed(val bytes: ByteArray, val pages: List<Page>, val declaredPages: Int)

    private fun parse(bytes: ByteArray): Parsed {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n"), "header")
        assertTrue(text.trimEnd().endsWith("%%EOF"), "trailer")
        val xrefAt = Regex("startxref\\n(\\d+)\\n%%EOF").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n", xrefAt), "startxref points at the table")
        val lines = text.substring(xrefAt).lines()
        val size = lines[1].split(" ")[1].toInt()
        val offsets = (1 until size).map { n ->
            val entry = lines[2 + n] // line 2 is the free entry
            assertEquals(20, entry.length + 1, "xref entries are 20 bytes with their line end: '$entry'")
            entry.take(10).toInt()
        }
        offsets.forEachIndexed { i, off -> assertTrue(text.startsWith("${i + 1} 0 obj\n", off), "object ${i + 1} sits at its offset $off") }

        fun obj(n: Int): String = text.substring(offsets[n - 1], text.indexOf("endobj", offsets[n - 1]))
        fun streamOf(n: Int): ByteArray {
            val head = obj(n)
            val lengthRef = Regex("/Length (\\d+)( 0 R)?").find(head)!!
            val length = if (lengthRef.groupValues[2].isNotEmpty()) obj(lengthRef.groupValues[1].toInt()).lines()[1].trim().toInt() else lengthRef.groupValues[1].toInt()
            val start = offsets[n - 1] + head.indexOf("stream\n") + "stream\n".length
            val after = text.substring(start + length, start + length + "\nendstream".length)
            assertEquals("\nendstream", after, "the declared length of object $n ends exactly at endstream")
            return bytes.copyOfRange(start, start + length)
        }

        val pagesObj = obj(2)
        val declared = Regex("/Count (\\d+)").find(pagesObj)!!.groupValues[1].toInt()
        val kids = Regex("(\\d+) 0 R").findAll(pagesObj.substringAfter("/Kids")).map { it.groupValues[1].toInt() }.toList()
        val pages = kids.map { k ->
            val page = obj(k)
            val box = Regex("/MediaBox \\[([^]]+)]").find(page)!!.groupValues[1].trim().split(" ").map(String::toDouble)
            val contents = Regex("/Contents (\\d+) 0 R").find(page)!!.groupValues[1].toInt()
            val imageRef = Regex("/Im0 (\\d+) 0 R").find(page)?.groupValues?.get(1)?.toInt()
            val image = imageRef?.let {
                val d = obj(it)
                val w = Regex("/Width (\\d+)").find(d)!!.groupValues[1].toInt()
                val h = Regex("/Height (\\d+)").find(d)!!.groupValues[1].toInt()
                val cs = Regex("/ColorSpace (/\\w+)").find(d)!!.groupValues[1]
                assertTrue("/Filter /FlateDecode" in d)
                Image(w, h, cs, InflaterInputStream(ByteArrayInputStream(streamOf(it))).readBytes())
            }
            Page(box, image, String(streamOf(contents), Charsets.US_ASCII))
        }
        return Parsed(bytes, pages, declared)
    }

    private fun write(plan: PrintPlan, document: SyntheticDocument, events: MutableList<JobEvent> = mutableListOf(), bandRows: Int = 7): Parsed {
        val out = ByteArrayOutputStream()
        val faces = RasterFaces.build(plan, plan.passes.first())
        PdfRasterBackend(bandRows).write(BackendJob(plan, document, SolidBlockRasterizer(), faces), out, events::add)
        return parse(out.toByteArray())
    }

    private fun plan(pages: Int, settings: PrintSettings = PrintSettings(), caps: io.github.zsozso01.platen.core.model.PrinterCapabilities = pdfOnly): Pair<PrintPlan, SyntheticDocument> {
        val doc = SyntheticDocument.a4Pages(pages, asPdf = false)
        return PrintPlanner.plan(doc, settings, caps) to doc
    }

    private fun Image.gray(x: Int, y: Int): Int = pixels[(y * width + x) * (if (colorSpace == "/DeviceGray") 1 else 3)].toInt() and 0xFF

    // --- tests ------------------------------------------------------------------------------------------

    @Test
    fun `every face is a full-sheet page holding its rendered image`() {
        val (plan, doc) = plan(3, PrintSettings(color = ColorMode.MONOCHROME))
        val events = mutableListOf<JobEvent>()
        val pdf = write(plan, doc, events)

        assertEquals(3, pdf.declaredPages)
        assertEquals(3, pdf.pages.size)
        pdf.pages.forEach { page ->
            assertEquals(listOf(0.0, 0.0, 595.28, 841.89), page.mediaBox.map { Math.round(it * 100) / 100.0 })
            val image = page.image!!
            assertEquals(413, image.width) // A4 at 50 dpi
            assertEquals(585, image.height)
            assertEquals("/DeviceGray", image.colorSpace)
            assertEquals(image.width * image.height, image.pixels.size)
            // The image is scaled to the whole sheet: a six-number matrix [w 0 0 h 0 0], then the image.
            assertEquals("q 595.28 0 0 841.89 0 0 cm /Im0 Do Q", page.content.trim())
        }
        assertEquals(listOf(1, 2, 3).map(SolidBlockRasterizer::shade), pdf.pages.map { it.image!!.gray(200, 300) })
        assertEquals<List<JobEvent>>(listOf(JobEvent.Preparing(1, 3), JobEvent.Preparing(2, 3), JobEvent.Preparing(3, 3)), events)
    }

    @Test
    fun `colour pages are RGB`() {
        val (plan, doc) = plan(1, PrintSettings(color = ColorMode.COLOR), colour)
        val image = write(plan, doc).pages.single().image!!
        assertEquals("/DeviceRGB", image.colorSpace)
        assertEquals(image.width * image.height * 3, image.pixels.size)
    }

    @Test
    fun `layout features are applied before writing`() {
        val (plan, doc) = plan(8, PrintSettings(pages = PageSelection(listOf(2..4)), reverseOrder = true, color = ColorMode.MONOCHROME))
        assertEquals(listOf(4, 3, 2).map(SolidBlockRasterizer::shade), write(plan, doc).pages.map { it.image!!.gray(200, 300) })
    }

    @Test
    fun `the padding face of an odd duplex job is a page with no image`() {
        val (plan, doc) = plan(3, PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = ColorMode.MONOCHROME))
        val pdf = write(plan, doc)
        assertEquals(4, pdf.pages.size)
        assertNull(pdf.pages.last().image)
        assertTrue(pdf.pages.take(3).all { it.image != null })
    }

    @Test
    fun `memory use is bounded by bands, not pages`() {
        // Whatever the band size, the same bytes describe the same pages.
        val (plan, doc) = plan(2, PrintSettings(color = ColorMode.COLOR), colour)
        val small = write(plan, doc, bandRows = 3)
        val large = write(plan, doc, bandRows = 500)
        assertEquals(small.pages.map { it.image!!.pixels.toList() }, large.pages.map { it.image!!.pixels.toList() })
    }

    @Test
    fun `a sample is left in the build directory for independent tools to check`() {
        val (plan, doc) = plan(3, PrintSettings(color = ColorMode.COLOR, sides = Sides.TWO_SIDED_LONG_EDGE), colour.copy(resolutionsDpi = listOf(100)))
        val out = ByteArrayOutputStream()
        PdfRasterBackend().write(BackendJob(plan, doc, SolidBlockRasterizer(), RasterFaces.build(plan, plan.passes.first())), out) {}
        val dir = File("build/samples").apply { mkdirs() }
        File(dir, "sample.pdf").writeBytes(out.toByteArray())
        assertTrue(File(dir, "sample.pdf").length() > 0)
    }
}
