package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.testing.support.SolidBlockRasterizer
import io.github.zsozso01.platen.testing.support.SyntheticDocument
import io.github.zsozso01.platen.testing.support.inkjetCaps
import io.github.zsozso01.platen.testing.support.laserCaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Records what the engine hands to a job protocol and plays back a scripted outcome. */
private class FakeProtocol(
    private val caps: PrinterCapabilities,
    private val script: (JobSubmission, CancelToken, (JobEvent) -> Unit) -> Unit = { _, _, l ->
        l(JobEvent.Accepted("1"))
        l(JobEvent.Completed)
    },
    private val probeFailure: IOException? = null,
) : JobProtocol {
    override val id = "fake"
    val submissions = mutableListOf<JobSubmission>()
    val payloadSizes = mutableListOf<Long>()
    var probes = 0

    override fun probe(): PrinterProbe {
        probes++
        probeFailure?.let { throw it }
        return PrinterProbe(caps, PrinterState.IDLE, emptyList(), "Fake")
    }

    override fun submit(submission: JobSubmission, cancel: CancelToken, listener: (JobEvent) -> Unit) {
        submissions += submission
        payloadSizes += submission.payload.length()
        script(submission, cancel, listener)
    }
}

/** A backend that writes a short marker so a spool file has content and a length. */
private class MarkerBackend(override val format: DocumentFormat, private val fail: Boolean = false) : Backend {
    var writes = 0
    val faceCounts = mutableListOf<Int>()

    override fun write(job: BackendJob, out: OutputStream, progress: (JobEvent) -> Unit) {
        writes++
        faceCounts += job.faces.size
        if (fail) throw IllegalStateException("renderer exploded")
        job.faces.forEachIndexed { i, _ -> progress(JobEvent.Preparing(i + 1, job.faces.size)) }
        out.write("RaS2".toByteArray())
    }
}

class PrintEngineTest {
    private val spool: File = createTempDirectory("platen-engine-test").toFile()

    @AfterTest
    fun cleanUp() {
        spool.deleteRecursively()
    }

    private fun engine(backend: Backend = MarkerBackend(DocumentFormat.PWG_RASTER), rasterizer: SideRasterizer? = SolidBlockRasterizer()) =
        PrintEngine(mapOf(backend.format to backend), spool, { rasterizer }, kotlinx.coroutines.Dispatchers.Unconfined)

    private fun events(engine: PrintEngine, request: PrintRequest): List<JobEvent> = runBlocking { engine.print(request).toList() }

    @Test
    fun `a simple raster job flows from preparing to completed and cleans up its spool file`() {
        val protocol = FakeProtocol(inkjetCaps)
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings()))
        assertEquals(listOf(JobEvent.Preparing(1, 2), JobEvent.Preparing(2, 2), JobEvent.Accepted("1"), JobEvent.Completed), list)
        assertEquals(1, protocol.submissions.size)
        assertEquals(DocumentFormat.PWG_RASTER, protocol.submissions.single().format)
        assertEquals(4, protocol.payloadSizes.single())
        assertTrue(spool.listFiles().isNullOrEmpty(), "spool file must be deleted")
    }

    @Test
    fun `capabilities are probed once unless supplied`() {
        val protocol = FakeProtocol(inkjetCaps)
        events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings()))
        assertEquals(1, protocol.probes)
        val known = FakeProtocol(inkjetCaps)
        events(engine(), PrintRequest(known, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings(), capabilities = inkjetCaps))
        assertEquals(0, known.probes)
    }

    @Test
    fun `a pdf is passed through unchanged without rendering`() {
        val protocol = FakeProtocol(laserCaps)
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(3), PrintSettings()))
        assertEquals(listOf(JobEvent.Accepted("1"), JobEvent.Completed), list)
        assertEquals(DocumentFormat.PDF, protocol.submissions.single().format)
        assertEquals(8, protocol.payloadSizes.single(), "the original %PDF-1.7 bytes")
    }

    @Test
    fun `an unreachable printer fails cleanly`() {
        val boom = IOException("no route to host")
        val list = events(engine(), PrintRequest(FakeProtocol(inkjetCaps, probeFailure = boom), SyntheticDocument.a4Pages(1), PrintSettings()))
        val failed = assertIs<JobEvent.Failed>(list.single())
        assertEquals(PrintFailure.Unreachable(boom), failed.failure)
    }

    @Test
    fun `planning problems are reported as preparation failures`() {
        val list = events(engine(), PrintRequest(FakeProtocol(inkjetCaps), SyntheticDocument.a4Pages(1), PrintSettings(pages = PageSelection(listOf(5..6)))))
        val failure = assertIs<JobEvent.Failed>(list.single()).failure
        assertIs<PrintFailure.PreparationFailed>(failure)
        assertTrue("selected" in failure.reason)
    }

    @Test
    fun `a rendering crash is reported, not thrown, and nothing is sent`() {
        val protocol = FakeProtocol(inkjetCaps)
        val list = events(engine(MarkerBackend(DocumentFormat.PWG_RASTER, fail = true)), PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings()))
        val failure = assertIs<JobEvent.Failed>(list.last()).failure
        assertIs<PrintFailure.PreparationFailed>(failure)
        assertTrue("renderer exploded" in failure.reason)
        assertTrue(protocol.submissions.isEmpty())
        assertTrue(spool.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a printer failure ends the job and is the only terminal event`() {
        val protocol = FakeProtocol(inkjetCaps, script = { _, _, l ->
            l(JobEvent.Failed(PrintFailure.Rejected("nope")))
            l(JobEvent.Completed) // a misbehaving protocol must not produce a second terminal event
        })
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings()))
        assertEquals(1, list.count { it is JobEvent.Failed || it == JobEvent.Completed })
        assertIs<JobEvent.Failed>(list.last())
    }

    @Test
    fun `manual duplex runs two passes and waits for the user between them`() {
        val protocol = FakeProtocol(inkjetCaps)
        val backend = MarkerBackend(DocumentFormat.PWG_RASTER)
        var reloadCalls = 0
        val list = events(
            engine(backend),
            PrintRequest(protocol, SyntheticDocument.a4Pages(4, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), awaitReload = {
                reloadCalls++
                true
            }),
        )
        assertEquals(2, protocol.submissions.size)
        assertEquals(listOf(2, 2), backend.faceCounts)
        assertEquals(1, reloadCalls)
        assertEquals(1, list.count { it == JobEvent.NeedsReload })
        // Exactly one Completed overall (the first pass's completion is held back), after the reload event.
        assertEquals(1, list.count { it == JobEvent.Completed })
        assertTrue(list.indexOf(JobEvent.NeedsReload) < list.indexOf(JobEvent.Completed))
        assertEquals(JobEvent.Completed, list.last())
    }

    @Test
    fun `declining to reload cancels the job before the back sides are sent`() {
        val protocol = FakeProtocol(inkjetCaps)
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE), awaitReload = { false }))
        assertEquals(1, protocol.submissions.size, "only the front side was sent")
        assertEquals(JobEvent.Canceled, list.last())
        assertTrue(list.none { it == JobEvent.Completed })
    }

    @Test
    fun `a failure in the front pass stops a manual duplex job`() {
        var calls = 0
        val protocol = FakeProtocol(inkjetCaps, script = { _, _, l ->
            calls++
            l(JobEvent.Failed(PrintFailure.Unreachable()))
        })
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(2, asPdf = false), PrintSettings(sides = Sides.TWO_SIDED_LONG_EDGE)))
        assertEquals(1, calls)
        assertIs<JobEvent.Failed>(list.last())
        assertTrue(list.none { it == JobEvent.NeedsReload })
    }

    @Test
    fun `attention events pass through without ending the job`() {
        val issue = PrinterIssue(PrinterIssue.Kind.PAPER_OUT, io.github.zsozso01.platen.core.model.Severity.ERROR, "media-empty-error")
        val protocol = FakeProtocol(inkjetCaps, script = { _, _, l ->
            l(JobEvent.Accepted("7"))
            l(JobEvent.Attention(listOf(issue)))
            l(JobEvent.Printing(1))
            l(JobEvent.Completed)
        })
        val list = events(engine(), PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings()))
        assertTrue(JobEvent.Attention(listOf(issue)) in list)
        assertEquals(JobEvent.Completed, list.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the collector cancels a send in progress`() = runTest {
        val sending = CompletableDeferred<Unit>()
        var sawCancel = false
        val protocol = FakeProtocol(inkjetCaps, script = { _, token, l ->
            l(JobEvent.Sending(0, 4))
            sending.complete(Unit)
            val deadline = System.currentTimeMillis() + 5_000
            while (!token.isCancelled && System.currentTimeMillis() < deadline) Thread.sleep(10)
            sawCancel = token.isCancelled
            l(JobEvent.Canceled)
        })
        val engine = PrintEngine(mapOf(DocumentFormat.PWG_RASTER to MarkerBackend(DocumentFormat.PWG_RASTER)), spool, { SolidBlockRasterizer() }, kotlinx.coroutines.Dispatchers.IO)
        val collected = mutableListOf<JobEvent>()
        val job: Job = launch(kotlinx.coroutines.Dispatchers.Default) {
            engine.print(PrintRequest(protocol, SyntheticDocument.a4Pages(1, asPdf = false), PrintSettings())).collect { collected += it }
        }
        sending.await()
        job.cancel()
        job.join()
        assertTrue(sawCancel, "the protocol must have been told to cancel")
        assertTrue(spool.listFiles().isNullOrEmpty())
    }
}
