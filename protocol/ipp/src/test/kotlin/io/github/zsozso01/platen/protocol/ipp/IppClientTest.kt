package io.github.zsozso01.platen.protocol.ipp

import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IppClientTest {
    private val printers = mutableListOf<FakeIppPrinter>()

    @AfterTest
    fun tearDown() = printers.forEach(FakeIppPrinter::close)

    private fun fake(profile: FakePrinterProfile) = FakeIppPrinter(profile).also(printers::add)

    private fun clientFor(printer: FakeIppPrinter, sendClose: Boolean = true) = IppClient(
        IppHttpTransport(printer.connector(), "127.0.0.1:${printer.port}", printer.path, sendConnectionClose = sendClose),
        printer.uri,
    )

    // --- capability detection ----------------------------------------------------------------

    @Test
    fun `reads capabilities of a raster-only inkjet`() {
        val a = clientFor(fake(FakePrinterProfile.inkjetRasterOnly)).getPrinterAttributes()
        assertFalse(a.supportsFormat(IppFormats.PDF))
        assertTrue(a.supportsFormat(IppFormats.PWG_RASTER))
        assertTrue(a.supportsFormat("IMAGE/URF"), "format match is case-insensitive")
        assertEquals(listOf("one-sided"), a.sidesSupported)
        assertEquals(IppRange(1, 99), a.copiesSupported)
        assertEquals(listOf(IppResolution(300, 300, IppResolution.Units.DOTS_PER_INCH)), a.pwgRasterResolutions)
        assertEquals(listOf("sgray_8", "srgb_8"), a.pwgRasterTypes)
        assertEquals(true, a.pageRangesSupported)
        assertEquals(IppPrinterState.IDLE, a.state)
        assertEquals(true, a.isAcceptingJobs)
        assertTrue(a.mediaSizes.any { it.keyword == "iso_a4_210x297mm" && it.widthHundredthsMm == 21000 })
        assertEquals(listOf(90, 50), a.markerLevels)
        assertEquals(listOf(IppVersion.V1_1, IppVersion.V2_0), a.ippVersions)
    }

    @Test
    fun `reads capabilities of a pdf laser`() {
        val a = clientFor(fake(FakePrinterProfile.laserPdf)).getPrinterAttributes()
        assertTrue(a.supportsFormat(IppFormats.PDF))
        assertEquals(listOf("one-sided", "two-sided-long-edge", "two-sided-short-edge"), a.sidesSupported)
        assertEquals(listOf("auto", "tray-1", "tray-2", "manual"), a.mediaSourcesSupported)
    }

    @Test
    fun `missing attributes read as empty not as errors`() {
        val a = clientFor(fake(FakePrinterProfile.laserPdf)).getPrinterAttributes(listOf("printer-name"))
        assertTrue(a.documentFormats.isEmpty())
        assertEquals(null, a.copiesSupported)
        assertEquals(null, a.makeAndModel)
        assertEquals("Fake LaserJet PDF", a.name)
    }

    // --- printing ----------------------------------------------------------------------------

    private val template = listOf(
        IppAttribute("copies", IppInteger(2)),
        IppAttribute("sides", IppString(IppString.Kind.KEYWORD, "one-sided")),
    )

    @Test
    fun `print job with known length is sent whole and byte exact`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val doc = Random(1).nextBytes(300_000)
        val progress = mutableListOf<Long>()
        val job = clientFor(printer).printJob(IppFormats.PDF, "My Report", template, IppDocument(ByteArrayInputStream(doc), doc.size.toLong()), progress::add)
        assertEquals(1, job.id)
        assertEquals(IppJobState.PENDING, job.state)
        val received = printer.jobs.single()
        assertContentEquals(doc, received.document)
        assertEquals("My Report", received.jobName)
        assertEquals(IppFormats.PDF, received.documentFormat)
        assertEquals(2, received.jobAttributes.first { it.name == "copies" }.int())
        assertEquals(doc.size.toLong(), progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b > a }, "progress is strictly increasing")
    }

    @Test
    fun `print job of unknown length is chunked and byte exact`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val doc = Random(2).nextBytes(100_000)
        clientFor(printer).printJob(IppFormats.PDF, "chunked", template, IppDocument(ByteArrayInputStream(doc), length = null))
        assertContentEquals(doc, printer.jobs.single().document)
    }

    @Test
    fun `empty document still works`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        clientFor(printer).printJob(IppFormats.PDF, "empty", emptyList(), IppDocument(ByteArrayInputStream(ByteArray(0)), 0))
        assertEquals(0, printer.jobs.single().document.size)
    }

    @Test
    fun `unsupported document format is reported with the rejected attribute`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        val e = assertFailsWith<IppOperationException> {
            clientFor(printer).printJob(IppFormats.PDF, "x", emptyList(), IppDocument(ByteArrayInputStream(byteArrayOf(1)), 1))
        }
        assertEquals(IppStatus.CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED, e.status)
        assertEquals("document-format", e.unsupportedAttributes.single().name)
        assertTrue(printer.jobs.isEmpty())
    }

    @Test
    fun `validate job detects substituted attributes`() {
        val printer = fake(FakePrinterProfile.inkjetRasterOnly)
        val ok = clientFor(printer).validateJob(IppFormats.PWG_RASTER, "t", listOf(IppAttribute("sides", IppString(IppString.Kind.KEYWORD, "one-sided"))))
        assertFalse(ok.hadSubstitutions)
        val subst = clientFor(printer).validateJob(IppFormats.PWG_RASTER, "t", listOf(IppAttribute("sides", IppString(IppString.Kind.KEYWORD, "two-sided-long-edge"))))
        assertTrue(subst.hadSubstitutions)
        assertEquals("sides", subst.unsupportedAttributes.single().name)
    }

    // --- jobs --------------------------------------------------------------------------------

    @Test
    fun `job progresses from processing to completed`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val client = clientFor(printer)
        val job = client.printJob(IppFormats.PDF, "j", emptyList(), IppDocument(ByteArrayInputStream(byteArrayOf(1)), 1))
        printer.behavior.pollsUntilCompleted = 2
        val states = (1..4).map { client.getJobAttributes(job.id) }
        assertEquals(listOf(5, 5, 9, 9), states.map { it.state })
        assertFalse(states[0].isTerminal)
        assertTrue(states[2].isTerminal)
        assertEquals(1, states[3].mediaSheetsCompleted)
    }

    @Test
    fun `cancel job`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val client = clientFor(printer)
        val job = client.printJob(IppFormats.PDF, "j", emptyList(), IppDocument(ByteArrayInputStream(byteArrayOf(1)), 1))
        client.cancelJob(job.id)
        assertEquals(IppJobState.CANCELED, client.getJobAttributes(job.id).state)
    }

    @Test
    fun `unknown job gives not-found`() {
        val e = assertFailsWith<IppOperationException> { clientFor(fake(FakePrinterProfile.laserPdf)).getJobAttributes(999) }
        assertEquals(IppStatus.CLIENT_ERROR_NOT_FOUND, e.status)
    }

    // --- protocol behaviour ------------------------------------------------------------------

    @Test
    fun `request starts with charset and language then printer-uri`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        clientFor(printer).getPrinterAttributes()
        val names = printer.requests.single().group(GroupTag.OPERATION_ATTRIBUTES)!!.attributes.map { it.name }
        assertEquals(listOf("attributes-charset", "attributes-natural-language", "printer-uri", "requesting-user-name"), names.take(4))
    }

    @Test
    fun `falls back to IPP 1_1 when the printer rejects 2_0`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.nextIppStatus = IppStatus.SERVER_ERROR_VERSION_NOT_SUPPORTED
        val client = clientFor(printer)
        assertNotNull(client.getPrinterAttributes())
        assertEquals(IppVersion.V1_1, client.protocolVersion)
        assertEquals(listOf(IppVersion.V2_0, IppVersion.V1_1), printer.requests.map { it.version })
    }

    @Test
    fun `retries with all attributes when a printer rejects the requested list`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.nextIppStatus = IppStatus.CLIENT_ERROR_BAD_REQUEST
        assertNotNull(clientFor(printer).getPrinterAttributes(listOf("something-weird")))
        assertEquals(2, printer.requests.size)
    }

    @Test
    fun `HTTP 426 and 401 are surfaced distinctly`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        val client = clientFor(printer)
        printer.behavior.httpStatus = 426
        assertTrue(assertFailsWith<IppHttpException> { client.getPrinterAttributes() }.needsTls)
        printer.behavior.httpStatus = 401
        assertTrue(assertFailsWith<IppHttpException> { client.getPrinterAttributes() }.needsAuthentication)
    }

    @Test
    fun `a connection dropped mid-document is an IOException not a hang`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        printer.behavior.dropAfterBodyBytes = 5_000
        val doc = ByteArray(2_000_000)
        assertFailsWith<IOException> {
            clientFor(printer).printJob(IppFormats.PDF, "drop", emptyList(), IppDocument(ByteArrayInputStream(doc), doc.size.toLong()))
        }
        assertTrue(printer.jobs.isEmpty())
    }

    @Test
    fun `document shorter than declared is rejected before the printer sees a bad job`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        assertFailsWith<IOException> {
            clientFor(printer).printJob(IppFormats.PDF, "short", emptyList(), IppDocument(ByteArrayInputStream(ByteArray(10)), 100))
        }
        assertTrue(printer.jobs.isEmpty())
    }

    @Test
    fun `connection-close header can be omitted for IPP-USB style transports`() {
        val printer = fake(FakePrinterProfile.laserPdf)
        assertNotNull(clientFor(printer, sendClose = false).getPrinterAttributes())
    }
}
