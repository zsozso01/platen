package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.backend.pdf.PdfRasterBackend
import io.github.zsozso01.platen.core.engine.PrintEngine
import io.github.zsozso01.platen.core.engine.PrintRequest
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.testing.support.SolidBlockRasterizer
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real planner and engine driving the PJL route against a fake PDF laser on a simulated USB interface. */
class PjlEndToEndTest {
    private val printer = FakePjlPrinter()
    private val spool: File = createTempDirectory("platen-pjl-e2e").toFile()

    @AfterTest
    fun tearDown() {
        printer.close()
        spool.deleteRecursively()
    }

    private val timing = PjlTiming(readSliceMillis = 10, flushMillis = 5, queryFirstByteMillis = 300, statusPollMillis = 40, settleMillis = 30)
    private val protocol = PjlJobProtocol(printer::open, deviceId = { "MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PCL,POSTSCRIPT,PDF;" }, timing = timing)

    private val engine = PrintEngine(
        backends = mapOf(DocumentFormat.PDF to PdfRasterBackend()),
        spoolDir = spool,
        rasterizer = { SolidBlockRasterizer() },
        dispatcher = Dispatchers.IO,
    )

    private fun run(request: PrintRequest): List<JobEvent> = runBlocking { engine.print(request).toList() }

    @Test
    fun `a PDF is probed, planned as pass-through and printed with its settings`() {
        val settings = PrintSettings(copies = 3, sides = Sides.TWO_SIDED_LONG_EDGE)
        val events = run(PrintRequest(protocol, SyntheticDocument.a4Pages(2), settings))

        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        val job = printer.jobs.single()
        assertEquals("PDF", job.language)
        assertEquals("%PDF-1.7", String(job.data), "the PDF went through unchanged")
        assertEquals("3", job.setting("QTY"))
        assertEquals("ON", job.setting("DUPLEX"))
        assertEquals("LONGEDGE", job.setting("BINDING"))
    }

    @Test
    fun `an image is rendered into a PDF and printed`() {
        val events = run(PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(copies = 2, collate = false)))
        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        val job = printer.jobs.single()
        assertEquals("PDF", job.language)
        assertTrue(String(job.data, Charsets.ISO_8859_1).startsWith("%PDF-1.4"), "a PDF made of the page image")
        assertEquals("2", job.setting("QTY"), "uncollated copies are left to the printer, carried by PJL")
    }

    @Test
    fun `collated copies are sent as repeated pages so they come out in order`() {
        val events = run(PrintRequest(protocol, SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(copies = 2)))
        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        val job = printer.jobs.single()
        assertTrue("/Count 4" in String(job.data, Charsets.ISO_8859_1))
        assertEquals(null, job.setting("QTY"))
    }

    @Test
    fun `a PDF with settings the printer cannot do natively is rendered so they still work`() {
        val settings = PrintSettings(reverseOrder = true)
        val events = run(PrintRequest(protocol, SyntheticDocument.a4Pages(3), settings))
        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        assertEquals(3, events.filterIsInstance<JobEvent.Preparing>().size, "all three pages were rendered")
        val pdf = String(printer.jobs.single().data, Charsets.ISO_8859_1)
        assertTrue(pdf.startsWith("%PDF-1.4") && "/Count 3" in pdf)
    }
}
