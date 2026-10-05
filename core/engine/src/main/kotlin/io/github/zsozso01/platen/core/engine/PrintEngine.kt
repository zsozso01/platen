package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Everything needed to print one document. */
public class PrintRequest(
    public val protocol: JobProtocol,
    public val document: DocumentSource,
    public val settings: PrintSettings,
    /** Skip the probe when the capabilities are already known (cached from the printer list). */
    public val capabilities: PrinterCapabilities? = null,
    public val jobName: String = document.name,
    public val manualDuplex: ManualDuplexOptions = ManualDuplexOptions(),
    /**
     * Called between the passes of a manual duplex job, after [JobEvent.NeedsReload]. Return true once the
     * user has reloaded the paper, false to stop. Not called for any other job.
     */
    public val awaitReload: suspend () -> Boolean = { true },
)

/**
 * Runs a print job: probe, plan, render and encode into a spool file, send, follow, report. Everything
 * the UI needs to show arrives as a [Flow] of [JobEvent]s that always ends with exactly one terminal
 * event ([JobEvent.Completed], [JobEvent.Canceled], [JobEvent.Failed] or [JobEvent.Detached]).
 * Cancelling the collector cancels the job, including a send that is in progress.
 */
public class PrintEngine(
    private val backends: Map<DocumentFormat, Backend>,
    private val spoolDir: File,
    /** Creates the rasteriser for one job; closed when the job ends. Null if the platform has none. */
    private val rasterizer: () -> SideRasterizer?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    public fun print(request: PrintRequest): Flow<JobEvent> = channelFlow {
        val token = CancelToken()
        // The work below blocks a thread, so it cannot see coroutine cancellation. This child can: it wakes the
        // moment the collector goes away and trips the token, which aborts the send in progress.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                token.cancel()
            }
        }
        try {
            withContext(dispatcher) {
                var terminal = false
                val emit: (JobEvent) -> Unit = { event ->
                    if (!terminal) {
                        if (event.isTerminal) terminal = true
                        trySendBlocking(event)
                    }
                }
                try {
                    run(request, token, emit)
                } catch (e: CancellationException) {
                    emit(JobEvent.Canceled)
                    throw e
                } catch (e: Throwable) {
                    emit(JobEvent.Failed(PrintFailure.Unexpected(e)))
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun run(request: PrintRequest, token: CancelToken, emit: (JobEvent) -> Unit) {
        val caps = request.capabilities ?: try {
            request.protocol.probe().capabilities
        } catch (e: IOException) {
            emit(JobEvent.Failed(PrintFailure.Unreachable(e)))
            return
        }

        val plan = try {
            PrintPlanner.plan(request.document, request.settings, caps, request.jobName)
        } catch (e: PlanningException) {
            emit(JobEvent.Failed(PrintFailure.PreparationFailed(e.message ?: "The job cannot be planned", e)))
            return
        }
        // Pass-through copies the original PDF and needs no writer; only the raster route does.
        val backend = if (plan.route == RouteKind.RASTER) backends[plan.format] else null
        if (plan.route == RouteKind.RASTER && backend == null) {
            emit(JobEvent.Failed(PrintFailure.PreparationFailed("No writer for ${plan.format.mime}")))
            return
        }

        val rasterizer = if (plan.route == RouteKind.RASTER) rasterizer() else null
        if (plan.route == RouteKind.RASTER && rasterizer == null) {
            emit(JobEvent.Failed(PrintFailure.PreparationFailed("This device cannot render pages")))
            return
        }
        try {
            val passes = if (plan.route == RouteKind.PASS_THROUGH) listOf(null) else plan.passes
            passes.forEachIndexed { index, pass ->
                if (token.isCancelled) {
                    emit(JobEvent.Canceled)
                    return
                }
                if (pass?.role == Pass.Role.BACKS) {
                    emit(JobEvent.NeedsReload)
                    if (!request.awaitReload() || token.isCancelled) {
                        emit(JobEvent.Canceled)
                        return
                    }
                }
                val isLast = index == passes.lastIndex
                val spool = File.createTempFile("platen-", ".job", spoolDir)
                try {
                    if (!prepare(plan, pass, request, backend, rasterizer, spool, emit)) return
                    var failed = false
                    request.protocol.submit(
                        JobSubmission(request.jobName, plan.format, spool, plan.printer, plan.raster),
                        token,
                    ) { event ->
                        when {
                            event == JobEvent.Completed && !isLast -> Unit // more passes to go
                            else -> {
                                if (event.isTerminal && event != JobEvent.Completed) failed = true
                                emit(event)
                            }
                        }
                    }
                    if (failed) return
                } finally {
                    spool.delete()
                }
            }
        } finally {
            rasterizer?.close()
        }
    }

    /** Writes the pass into [spool]. Returns false (after reporting) if that failed. */
    private fun prepare(
        plan: PrintPlan,
        pass: Pass?,
        request: PrintRequest,
        backend: Backend?,
        rasterizer: SideRasterizer?,
        spool: File,
        emit: (JobEvent) -> Unit,
    ): Boolean = try {
        spool.outputStream().buffered().use { out ->
            if (plan.route == RouteKind.PASS_THROUGH) {
                val pdf = requireNotNull(request.document.pdf) { "Pass-through needs a PDF" }
                pdf.open().use { it.copyTo(out) }
            } else {
                val faces = RasterFaces.build(plan, requireNotNull(pass), request.manualDuplex)
                requireNotNull(backend).write(BackendJob(plan, request.document, rasterizer, faces), out, emit)
            }
        }
        true
    } catch (e: IOException) {
        emit(JobEvent.Failed(PrintFailure.PreparationFailed("Could not prepare the document: ${e.message}", e)))
        false
    } catch (e: RuntimeException) {
        emit(JobEvent.Failed(PrintFailure.PreparationFailed("Could not render the document: ${e.message}", e)))
        false
    }

    private val JobEvent.isTerminal: Boolean
        get() = this == JobEvent.Completed || this == JobEvent.Canceled || this is JobEvent.Failed || this is JobEvent.Detached
}
