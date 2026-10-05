package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.backend.raster.PwgRasterBackend
import io.github.zsozso01.platen.core.engine.PrintEngine
import io.github.zsozso01.platen.core.engine.PrintRequest
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.ipp.IppEnum
import io.github.zsozso01.platen.protocol.ipp.IppOperation
import io.github.zsozso01.platen.protocol.ipp.IppResolution
import io.github.zsozso01.platen.protocol.ipp.IppStatus
import io.github.zsozso01.platen.protocol.raster.PwgRasterReader
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.testing.fakeprinter.ReceivedJob
import io.github.zsozso01.platen.testing.support.SolidBlockRasterizer
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real stack against a fake printer on a real socket: planner, raster backend, PWG writer, IPP
 * client and the IPP job protocol, with only the page *contents* synthetic.
 */
class EndToEndTest {
    private val printers = mutableListOf<FakeIppPrinter>()
    private val spool: File = createTempDirectory("platen-e2e").toFile()

    @AfterTest
    fun tearDown() {
        printers.forEach(FakeIppPrinter::close)
        spool.deleteRecursively()
    }

    private fun fake(profile: FakePrinterProfile) = FakeIppPrinter(profile).also(printers::add)

    private fun endpoint(printer: FakeIppPrinter) =
        IppEndpoint(printer.connector(), "127.0.0.1:${printer.port}", printer.path, printer.uri)

    private fun protocol(printer: FakeIppPrinter, sleeper: (Long) -> Unit = {}, clock: () -> Long = System::currentTimeMillis, maxSilent: Long = 60_000) =
        IppJobProtocol(endpoint(printer), pollIntervalMillis = 100, maxSilentMillis = maxSilent, sleeper = sleeper, clock = clock)

    private val engine = PrintEngine(
        backends = mapOf(DocumentFormat.PWG_RASTER to PwgRasterBackend()),
        spoolDir = spool,
        rasterizer = { SolidBlockRasterizer() },
        dispatcher = kotlinx.coroutines.Dispatchers.IO,
    )

    private fun run(request: PrintRequest): List<JobEvent> = runBlocking { engine.print(request).toList() }

    private fun attr(job: ReceivedJob, name: String) = job.jobAttributes.firstOrNull { it.name == name }

    private fun pwgPages(job: ReceivedJob): List<io.github.zsozso01.platen.protocol.raster.PwgPageHeader> {
        val reader = PwgRasterReader(ByteArrayInputStream(job.document))
        return generateSequence { reader.nextPage() }.toList()
    }

    // --- capability probe -------------------------------------------------------------------------

    @Test
    fun `probing a raster-only inkjet maps its attributes to capabilities`() {
        val caps = protocol(fake(FakePrinterProfile.inkjetRasterOnly)).probe().capabilities
        assertTrue(caps.supports(DocumentFormat.PWG_RASTER))
        assertTrue(!caps.supports(DocumentFormat.PDF))
        assertEquals(setOf(Sides.ONE_SIDED), caps.sides)
        assertEquals(listOf(300), caps.resolutionsDpi)
        assertEquals(99, caps.maxCopies)
        assertTrue(caps.supportsPageRanges)
        assertEquals(setOf(Quality.DRAFT, Quality.NORMAL, Quality.HIGH), caps.qualities)
        assertEquals(io.github.zsozso01.platen.core.model.Margins(top = 296, right = 296, bottom = 1270, left = 296), caps.unprintableMargins)
        assertEquals(listOf("sgray_8", "srgb_8"), caps.raster!!.colorTypes)
        assertEquals("rotated", caps.raster!!.sheetBack)
        assertEquals("iso_a4_210x297mm", caps.defaultMedia!!.pwgName)
        assertEquals(listOf("tri-color ink", "black ink"), caps.supplies.map { it.name })
        assertEquals(90, caps.supplies.first().levelPercent)
        assertEquals(io.github.zsozso01.platen.core.model.Provenance.REPORTED_IPP, caps.provenanceOf(io.github.zsozso01.platen.core.model.CapabilityKey.FORMATS))
    }

    @Test
    fun `probing a PDF laser maps duplex, trays and layout features`() {
        val probe = protocol(fake(FakePrinterProfile.laserPdf)).probe()
        val caps = probe.capabilities
        assertTrue(caps.supports(DocumentFormat.PDF))
        assertEquals(setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE, Sides.TWO_SIDED_SHORT_EDGE), caps.sides)
        assertEquals(listOf("auto", "tray-1", "tray-2", "manual"), caps.trays.map { it.id })
        assertEquals(999, caps.maxCopies)
        assertEquals(listOf(300, 600), caps.resolutionsDpi)
        assertEquals(io.github.zsozso01.platen.core.model.PrinterState.IDLE, probe.state)
        assertTrue(probe.issues.isEmpty())
        assertEquals("Fake LaserJet PDF", probe.makeAndModel)
    }

    // --- printing ---------------------------------------------------------------------------------

    @Test
    fun `printing to a raster-only inkjet sends a correct PWG raster job`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(color = ColorMode.COLOR), jobName = "Report"))

        assertEquals(JobEvent.Completed, events.last())
        assertTrue(events.any { it is JobEvent.Sending }, "upload progress was reported")
        assertTrue(events.any { it is JobEvent.Accepted })
        val job = printer.jobs.single()
        assertEquals("Report", job.jobName)
        assertEquals("image/pwg-raster", job.documentFormat)
        val pages = pwgPages(job)
        assertEquals(2, pages.size)
        pages.forEach {
            assertEquals(2480, it.widthPixels, "A4 at 300 dpi")
            assertEquals(3508, it.heightPixels)
            assertEquals("srgb_8", it.colorType.keyword)
        }
        assertEquals("one-sided", attr(job, "sides")!!.string())
        assertEquals("iso_a4_210x297mm", attr(job, "media")!!.string())
        assertEquals(IppResolution(300, 300, IppResolution.Units.DOTS_PER_INCH), attr(job, "printer-resolution")!!.resolutions().single())
        assertEquals("none", attr(job, "print-scaling")!!.string())
        assertEquals(3, attr(job, "orientation-requested")!!.int())
        assertEquals("color", attr(job, "print-color-mode")!!.string())
        assertEquals(null, attr(job, "copies"), "one copy needs no attribute")
        assertTrue(spool.listFiles().isNullOrEmpty())
    }

    @Test
    fun `printing a PDF to a PDF laser sends the original bytes and the printer's own settings`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(3), PrintSettings(copies = 2, sides = Sides.TWO_SIDED_LONG_EDGE)))
        assertEquals(JobEvent.Completed, events.last())
        val job = printer.jobs.single()
        assertEquals("application/pdf", job.documentFormat)
        assertContentEquals("%PDF-1.7".toByteArray(), job.document)
        assertEquals(2, attr(job, "copies")!!.int())
        assertEquals("two-sided-long-edge", attr(job, "sides")!!.string())
    }

    @Test
    fun `manual duplex on the inkjet sends two jobs with a reload between`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        var reloads = 0
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(4, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE, color = ColorMode.MONOCHROME), awaitReload = { reloads++; true }))
        assertEquals(JobEvent.Completed, events.last())
        assertEquals(1, reloads)
        assertEquals(1, events.count { it == JobEvent.NeedsReload })
        assertEquals(2, printer.jobs.size)
        assertEquals(listOf(2, 2), printer.jobs.map { pwgPages(it).size })
        // Both jobs are one-sided: the printer never sees duplex.
        assertTrue(printer.jobs.all { attr(it, "sides")!!.string() == "one-sided" })
    }

    @Test
    fun `collated copies are sent as repeated pages, not as a copies attribute`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(copies = 3, color = ColorMode.MONOCHROME)))
        val job = printer.jobs.single()
        assertEquals(6, pwgPages(job).size)
        assertEquals(null, attr(job, "copies"))
    }

    @Test
    fun `uncollated copies use the copies attribute`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(copies = 3, collate = false, color = ColorMode.MONOCHROME)))
        val job = printer.jobs.single()
        assertEquals(2, pwgPages(job).size)
        assertEquals(3, attr(job, "copies")!!.int())
    }

    // --- following and failing ----------------------------------------------------------------------

    @Test
    fun `an attribute the printer rejects is dropped and the job is retried once`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.rejectAttribute = "print-quality"
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(1), PrintSettings(quality = Quality.HIGH)))
        assertEquals(JobEvent.Completed, events.last())
        assertEquals(null, attr(printer.jobs.single(), "print-quality"))
        assertEquals(2, printer.requests.count { it.code == IppOperation.PRINT_JOB })
    }

    @Test
    fun `an unreachable printer is reported as unreachable`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val port = printer.port
        printer.close() // nothing is listening any more
        val dead = IppJobProtocol(IppEndpoint({ java.net.Socket("127.0.0.1", port).let { s -> object : io.github.zsozso01.platen.protocol.ipp.IppConnection { override val input = s.getInputStream(); override val output = s.getOutputStream(); override fun close() = s.close() } } }, "127.0.0.1:$port", "/ipp/print", "ipp://127.0.0.1:$port/ipp/print"))
        val events = run(PrintRequest(dead, SyntheticDocument.a4Pages(1), PrintSettings()))
        assertIs<PrintFailure.Unreachable>(assertIs<JobEvent.Failed>(events.single()).failure)
    }

    @Test
    fun `authentication and encryption demands are reported distinctly`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val caps = protocol(printer).probe().capabilities
        printer.behavior.httpStatus = 401
        val auth = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(1), PrintSettings(), capabilities = caps))
        assertIs<PrintFailure.AuthenticationRequired>(assertIs<JobEvent.Failed>(auth.last()).failure)
        printer.behavior.httpStatus = 426
        val tls = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(1), PrintSettings(), capabilities = caps))
        assertIs<PrintFailure.EncryptionRequired>(assertIs<JobEvent.Failed>(tls.last()).failure)
    }

    @Test
    fun `a busy printer is reported as needing attention`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val caps = protocol(printer).probe().capabilities
        printer.behavior.nextIppStatus = IppStatus.SERVER_ERROR_BUSY
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(1), PrintSettings(), capabilities = caps))
        val failure = assertIs<PrintFailure.NeedsAttention>(assertIs<JobEvent.Failed>(events.last()).failure)
        assertEquals(PrinterIssue.Kind.BUSY, failure.issues.single().kind)
    }

    @Test
    fun `a dropped upload fails as unreachable and leaves nothing behind`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        val caps = protocol(printer).probe().capabilities
        printer.behavior.dropAfterBodyBytes = 2_000
        val events = run(PrintRequest(protocol(printer), SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(color = ColorMode.COLOR), capabilities = caps))
        assertIs<PrintFailure.Unreachable>(assertIs<JobEvent.Failed>(events.last()).failure)
        assertTrue(printer.jobs.isEmpty())
        assertTrue(spool.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a stopped printer is reported as attention and the job still completes once fixed`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.jobStopped = true
        printer.behavior.printerState = 5
        printer.behavior.printerStateReasons = listOf("media-empty-error")
        var sleeps = 0
        val sleeper: (Long) -> Unit = {
            if (++sleeps == 12) { // the user adds paper
                printer.behavior.jobStopped = false
                printer.behavior.printerState = 3
                printer.behavior.printerStateReasons = listOf("none")
            }
        }
        val events = run(PrintRequest(protocol(printer, sleeper), SyntheticDocument.a4Pages(1), PrintSettings()))
        val attention = events.filterIsInstance<JobEvent.Attention>()
        assertTrue(attention.isNotEmpty(), "paper-out must be surfaced")
        assertEquals(PrinterIssue.Kind.PAPER_OUT, attention.first().issues.first().kind)
        assertEquals(JobEvent.Completed, events.last())
    }

    @Test
    fun `a printer that stops answering after accepting the job is detached, not failed`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        var now = 0L
        val protocol = protocol(printer, sleeper = { now += it }, clock = { now }, maxSilent = 5_000)
        printer.behavior.pollsUntilCompleted = 1_000
        val events = mutableListOf<JobEvent>()
        val flipper: (JobEvent) -> Unit = { events += it; if (it is JobEvent.Accepted) printer.behavior.httpStatus = 503 }
        val file = File.createTempFile("job", ".pdf", spool).apply { writeText("%PDF-1.7") }
        protocol.submit(
            io.github.zsozso01.platen.core.engine.JobSubmission("x", DocumentFormat.PDF, file, io.github.zsozso01.platen.core.engine.PrinterJobSettings("x"), null),
            io.github.zsozso01.platen.core.engine.CancelToken(),
            flipper,
        )
        assertIs<JobEvent.Detached>(events.last())
        assertTrue(events.none { it is JobEvent.Failed })
    }

    @Test
    fun `cancelling after the job was accepted cancels it on the printer`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.pollsUntilCompleted = 1_000
        val token = io.github.zsozso01.platen.core.engine.CancelToken()
        val file = File.createTempFile("job", ".pdf", spool).apply { writeText("%PDF-1.7") }
        val events = mutableListOf<JobEvent>()
        protocol(printer).submit(
            io.github.zsozso01.platen.core.engine.JobSubmission("x", DocumentFormat.PDF, file, io.github.zsozso01.platen.core.engine.PrinterJobSettings("x"), null),
            token,
        ) { events += it; if (it is JobEvent.Accepted) token.cancel() }
        assertEquals(JobEvent.Canceled, events.last())
        assertTrue(printer.requests.any { it.code == IppOperation.CANCEL_JOB }, "Cancel-Job reached the printer")
        assertNotNull(printer.jobs.singleOrNull())
        assertEquals(IppEnum(7).value, 7)
    }
}
