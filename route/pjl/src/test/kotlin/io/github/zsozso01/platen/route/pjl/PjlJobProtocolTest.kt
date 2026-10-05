package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlVariable
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PjlJobProtocolTest {
    private val printers = mutableListOf<FakePjlPrinter>()
    private val spool: File = createTempDirectory("platen-pjl").toFile()

    @AfterTest
    fun tearDown() {
        printers.forEach(FakePjlPrinter::close)
        spool.deleteRecursively()
    }

    private val fast = PjlTiming(
        readSliceMillis = 10,
        flushMillis = 5,
        queryFirstByteMillis = 300,
        queryTotalMillis = 2_000,
        statusPollMillis = 40,
        settleMillis = 30,
        maxSilentMillis = 400,
        writeChunkBytes = 64,
    )

    private fun fake(
        languages: List<String> = listOf("PCL", "POSTSCRIPT", "PDF"),
        answersPjl: Boolean = true,
        bidirectional: Boolean = true,
        honoursUstatus: Boolean = true,
        variables: List<FakePjlVariable> = FakePjlPrinter.defaultVariables(),
    ) = FakePjlPrinter(
        languages = languages,
        answersPjl = answersPjl,
        bidirectional = bidirectional,
        honoursUstatus = honoursUstatus,
        variables = variables,
    ).also(printers::add)

    private val deviceId = "MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF;CLS:PRINTER;"

    private fun protocol(
        open: () -> ByteChannel,
        timing: PjlTiming = fast,
        wrapInPjl: Boolean = true,
        ioFailure: (IOException) -> PrintFailure = { PrintFailure.Unreachable(it) },
    ) = PjlJobProtocol(open, deviceId = { deviceId }, timing = timing, wrapInPjl = wrapInPjl, ioFailure = ioFailure)

    private fun protocol(printer: FakePjlPrinter, wrapInPjl: Boolean = true) = protocol(printer::open, wrapInPjl = wrapInPjl)

    private fun submission(
        payload: ByteArray = "%PDF-1.7 payload that is longer than one chunk of sixty-four bytes, to exercise chunking".toByteArray(),
        settings: PrinterJobSettings = PrinterJobSettings(jobName = "Doc"),
        format: DocumentFormat = DocumentFormat.PDF,
    ): JobSubmission {
        val file = File.createTempFile("job", ".bin", spool).also { it.writeBytes(payload) }
        return JobSubmission(settings.jobName, format, file, settings, raster = null)
    }

    private fun run(
        protocol: PjlJobProtocol,
        submission: JobSubmission = submission(),
        cancel: CancelToken = CancelToken(),
        onEvent: (JobEvent) -> Unit = {},
    ): List<JobEvent> {
        val events = CopyOnWriteArrayList<JobEvent>()
        protocol.submit(submission, cancel) { events += it; onEvent(it) }
        return events
    }

    private fun List<JobEvent>.terminal(): JobEvent = last()

    /** The fake reads its input on its own thread, so a write-only job can complete before it has parsed it. */
    private fun awaitJobs(printer: FakePjlPrinter, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (printer.jobs.size < count && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(count, printer.jobs.size)
    }

    // --- probing ----------------------------------------------------------------------------------

    @Test
    fun `probe reads what the printer reports`() {
        val printer = fake()
        val probe = protocol(printer).probe()
        assertEquals("Fake LaserJet", probe.makeAndModel)
        assertTrue(probe.capabilities.supports(DocumentFormat.PDF))
        assertEquals(999, probe.capabilities.maxCopies)
        assertTrue(probe.capabilities.canDuplex)
        assertEquals(PrinterState.IDLE, probe.state)
        assertTrue(probe.issues.isEmpty())
    }

    @Test
    fun `probe reports a printer that needs attention`() {
        val printer = fake().also { it.deviceCode = 41002; it.deviceDisplay = "LOAD PAPER TRAY 1" }
        val probe = protocol(printer).probe()
        assertEquals(PrinterState.STOPPED, probe.state)
        assertEquals(PrinterIssue.Kind.PAPER_OUT, probe.issues.single().kind)
    }

    @Test
    fun `a printer that does not speak PJL still yields its device id capabilities`() {
        val printer = fake(answersPjl = false)
        val started = System.nanoTime()
        val probe = protocol(printer).probe()
        assertTrue(probe.capabilities.supports(DocumentFormat.PDF), "device id says PDF")
        assertEquals(PrinterState.UNKNOWN, probe.state)
        assertEquals("HP LaserJet MFP E42540", probe.makeAndModel)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500, "gave up after the first-byte timeout")
    }

    @Test
    fun `a unidirectional interface is probed without waiting`() {
        val printer = fake(bidirectional = false)
        val started = System.nanoTime()
        val probe = protocol(printer).probe()
        assertTrue(probe.capabilities.supports(DocumentFormat.PDF))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250)
    }

    @Test
    fun `raw mode does not touch the printer to probe`() {
        val printer = fake()
        protocol(printer, wrapInPjl = false).probe()
        assertEquals(0, printer.connections)
    }

    // --- sending ------------------------------------------------------------------------------------

    @Test
    fun `a job is wrapped in PJL, delivered intact and completes on the printer's end report`() {
        val printer = fake()
        val payload = ByteArray(1_000) { (it * 7).toByte() }
        val events = run(protocol(printer), submission(payload))

        assertIs<JobEvent.Sending>(events.first())
        assertEquals(JobEvent.Completed, events.terminal())
        val order = events.map { it::class.simpleName }
        assertTrue(order.indexOf("Accepted") < order.indexOf("Printing"), "Printing is reported after Accepted: $order")
        assertTrue(events.filterIsInstance<JobEvent.Sending>().last().let { it.bytesSent == 1_000L })

        val job = printer.jobs.single()
        assertEquals("PDF", job.language)
        assertContentEquals(payload, job.data)
        assertTrue(job.ended)
        assertTrue("@PJL USTATUS JOB=ON" in printer.allPjlLines)
        assertTrue(printer.allPjlLines.any { it.startsWith("@PJL JOB NAME=\"Doc\"") })
    }

    @Test
    fun `settings the printer lists are carried in the job and unknown ones are not`() {
        val printer = fake()
        val settings = PrinterJobSettings(
            jobName = "Doc",
            copies = 2,
            sides = Sides.TWO_SIDED_LONG_EDGE,
            paper = MediaSize.A5,
            resolutionDpi = 1200,
            tray = io.github.zsozso01.platen.core.model.MediaSource("TRAY9"),
        )
        run(protocol(printer), submission(settings = settings))
        val job = printer.jobs.single()
        assertEquals("2", job.setting("QTY"), "collated job copies are preferred when the printer has QTY")
        assertNull(job.setting("COPIES"))
        assertEquals("ON", job.setting("DUPLEX"))
        assertEquals("LONGEDGE", job.setting("BINDING"))
        assertEquals("A5", job.setting("PAPER"))
        assertEquals("1200", job.setting("RESOLUTION"))
        assertNull(job.setting("MEDIASOURCE"), "TRAY9 is not one of the printer's trays")
    }

    @Test
    fun `copies use COPIES when the printer has no QTY`() {
        val printer = fake(variables = FakePjlPrinter.defaultVariables().filter { it.name != "QTY" })
        run(protocol(printer), submission(settings = PrinterJobSettings(jobName = "Doc", copies = 4)))
        val job = printer.jobs.single()
        assertEquals("4", job.setting("COPIES"))
        assertNull(job.setting("QTY"))
    }

    @Test
    fun `a printer that ignores job reports completes once it says ready twice`() {
        val printer = fake(honoursUstatus = false)
        val events = run(protocol(printer))
        assertEquals(JobEvent.Completed, events.terminal())
        assertTrue(events.none { it is JobEvent.Printing }, "no start report was received")
        assertEquals(1, printer.jobs.size)
        assertTrue(printer.allPjlLines.count { it == "@PJL INFO STATUS" } >= 2)
    }

    @Test
    fun `a printer that answers nothing is treated as finished once the data is accepted`() {
        val printer = fake(answersPjl = false)
        val events = run(protocol(printer))
        assertEquals(JobEvent.Completed, events.terminal())
        assertEquals(1, printer.jobs.size)
    }

    @Test
    fun `a write-only interface completes right after the last byte and asks for no reports`() {
        val printer = fake(bidirectional = false)
        val events = run(protocol(printer))
        assertEquals(JobEvent.Completed, events.terminal())
        awaitJobs(printer, 1)
        assertTrue(printer.allPjlLines.none { it.startsWith("@PJL USTATUS") }, "nothing could be heard, so nothing is requested")
    }

    @Test
    fun `raw mode sends the document and nothing else`() {
        val written = java.io.ByteArrayOutputStream()
        val channel = object : ByteChannel {
            override fun write(data: ByteArray, offset: Int, length: Int) = written.write(data, offset, length)
            override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int) = -1
            override fun close() {}
        }
        val payload = "%PDF-1.7 raw".toByteArray()
        val events = run(protocol({ channel }, wrapInPjl = false), submission(payload))
        assertEquals(JobEvent.Completed, events.terminal())
        assertContentEquals(payload, written.toByteArray())
    }

    @Test
    fun `leftover bytes from an earlier session are not mistaken for this job`() {
        val printer = fake()
        printer.pushToHost("@PJL USTATUS JOB\r\nEND\r\nNAME=\"Doc\"\r\nPAGES=1\r\n\u000C")
        var jobsAtCompletion = -1
        val events = run(protocol(printer)) { if (it == JobEvent.Completed) jobsAtCompletion = printer.jobs.size }
        assertEquals(JobEvent.Completed, events.terminal())
        assertEquals(1, jobsAtCompletion, "completion was reported only after the printer had the job")
    }

    @Test
    fun `an unsupported format is refused before the printer is touched`() {
        val printer = fake()
        val events = run(protocol(printer), submission(format = DocumentFormat.PWG_RASTER))
        val failed = assertIs<JobEvent.Failed>(events.single())
        assertIs<PrintFailure.Rejected>(failed.failure)
        assertEquals(0, printer.connections)
    }

    // --- what the printer says while printing -----------------------------------------------------------

    @Test
    fun `paper out is shown as attention and clears when the printer is ready again`() {
        val printer = fake()
        printer.scriptedMessages.put("@PJL USTATUS DEVICE\r\nCODE=41002\r\nDISPLAY=\"LOAD PAPER TRAY 1\"\r\nONLINE=FALSE\r\n\u000C")
        printer.scriptedMessages.put("@PJL USTATUS DEVICE\r\nCODE=10001\r\nDISPLAY=\"00 READY\"\r\nONLINE=TRUE\r\n\u000C")
        val events = run(protocol(printer))
        val attention = events.filterIsInstance<JobEvent.Attention>().single()
        assertEquals(PrinterIssue.Kind.PAPER_OUT, attention.issues.single().kind)
        val at = events.indexOf(attention)
        assertTrue(events.drop(at + 1).any { it is JobEvent.Printing }, "printing resumes after the paper is loaded: $events")
        assertEquals(JobEvent.Completed, events.terminal())
    }

    @Test
    fun `a job that ends with no pages is a failure, not a success`() {
        val printer = fake().also { it.reportedPages = 0 }
        val failed = assertIs<JobEvent.Failed>(run(protocol(printer)).terminal())
        assertIs<PrintFailure.Rejected>(failed.failure)
    }

    @Test
    fun `a printer that talks and then goes quiet is detached from, not failed`() {
        val printer = fake().also { it.reportsJobEnd = false }
        val events = run(protocol(printer)) { if (it is JobEvent.Printing) printer.muted = true }
        assertIs<JobEvent.Detached>(events.terminal())
    }

    @Test
    fun `a connection that drops after the data was sent is detached from`() {
        val printer = fake().also { it.reportsJobEnd = false }
        val open = {
            val inner = printer.open()
            object : ByteChannel by inner {
                @Volatile var footerSent = false
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    inner.write(data, offset, length)
                    if (String(data, offset, length, Charsets.ISO_8859_1).contains("@PJL EOJ")) footerSent = true
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int): Int =
                    if (footerSent) { Thread.sleep(20); -1 } else inner.read(buffer, offset, length, timeoutMillis)
            }
        }
        assertIs<JobEvent.Detached>(run(protocol(open)).terminal())
    }

    @Test
    fun `a job cancelled at the printer's panel is reported as cancelled`() {
        val printer = fake().also { it.reportsJobEnd = false }
        printer.scriptedMessages.put("@PJL USTATUS JOB\r\nCANCELED\r\nNAME=\"Doc\"\r\n\u000C")
        assertEquals(JobEvent.Canceled, run(protocol(printer)).terminal())
    }

    // --- failures and cancelling ------------------------------------------------------------------------

    @Test
    fun `a failing write is reported through the failure mapper`() {
        val broken = object : ByteChannel {
            override fun write(data: ByteArray, offset: Int, length: Int) = throw IOException("device gone")
            override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int) = 0
            override fun close() {}
        }
        val plain = run(protocol({ broken }))
        assertIs<PrintFailure.Unreachable>(assertIs<JobEvent.Failed>(plain.terminal()).failure)

        val usb = run(protocol({ broken }, ioFailure = { PrintFailure.UsbProblem("unplugged", it) }))
        assertEquals("unplugged", (assertIs<JobEvent.Failed>(usb.terminal()).failure as PrintFailure.UsbProblem).reason)
    }

    @Test
    fun `cancelling while the data is going out closes the job on a fresh connection`() {
        val printer = fake()
        val blocked = CountDownLatch(1)
        val open = {
            val inner = printer.open()
            object : ByteChannel by inner {
                private val gate = CountDownLatch(1)
                private var writes = 0
                override fun write(data: ByteArray, offset: Int, length: Int) {
                    // let the header and the first chunk through, then hang as a full printer buffer would
                    if (++writes > 2 && printer.connections == 1) {
                        blocked.countDown()
                        gate.await()
                        throw IOException("closed")
                    }
                    inner.write(data, offset, length)
                }
                override fun close() {
                    gate.countDown()
                    inner.close()
                }
            }
        }
        val cancel = CancelToken()
        thread { blocked.await(); cancel.cancel() }
        val events = run(protocol(open), submission(ByteArray(10_000)), cancel)
        assertEquals(JobEvent.Canceled, events.terminal())
        assertEquals(2, printer.connections, "the job was closed on a second connection")
        assertTrue(printer.jobs.single().ended, "the printer saw the end of the job")
    }

    @Test
    fun `cancelling after the printer has the whole job tells the user to use the printer's button`() {
        val printer = fake().also { it.reportsJobEnd = false }
        val cancel = CancelToken()
        val events = run(protocol(printer), cancel = cancel) { if (it is JobEvent.Printing) cancel.cancel() }
        val detached = assertIs<JobEvent.Detached>(events.terminal())
        assertTrue("cancel button" in detached.reason)
        assertFalse(events.any { it == JobEvent.Canceled })
        assertNotNull(printer.jobs.singleOrNull())
    }
}
