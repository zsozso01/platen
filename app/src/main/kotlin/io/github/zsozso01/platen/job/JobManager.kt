package io.github.zsozso01.platen.job

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import io.github.zsozso01.platen.backend.pdf.PdfRasterBackend
import io.github.zsozso01.platen.backend.raster.PwgRasterBackend
import io.github.zsozso01.platen.core.engine.PrintEngine
import io.github.zsozso01.platen.core.engine.ManualDuplexOptions
import io.github.zsozso01.platen.core.engine.Pass
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.RouteKind
import io.github.zsozso01.platen.core.engine.PrintRequest
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.data.SavedPrinter
import io.github.zsozso01.platen.platform.render.AndroidSideRasterizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the UI shows about the job that is running or just finished. There is at most one at a time. */
data class JobUiState(
    val documentName: String,
    val printerName: String,
    /** Latest non-terminal event, for the status line. */
    val latest: JobEvent? = null,
    /** Set once the job ended: Completed, Canceled, Failed or Detached. */
    val finished: JobEvent? = null,
    /** True while the user must reload paper before the back sides print. */
    val waitingForReload: Boolean = false,
    /** Highest page count seen, so progress can be shown as a fraction. */
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /** Side index of every face in the pass being printed, in print order (null = blank face). Empty if unknown. */
    val faces: List<Int?> = emptyList(),
    /** Small pictures of the laid-out sides, filled in as they are rendered, keyed by side index. */
    val thumbs: Map<Int, Bitmap> = emptyMap(),
    /** 0..1 share of the pass data handed to the printer. */
    val sendFraction: Float = 0f,
    /** When data first went to the printer, in [System.currentTimeMillis]. */
    val printStartMs: Long? = null,
    val sheetsDone: Int? = null,
    val sheetDoneAtMs: Long? = null,
    /** Estimated time the printer needs for one sheet; learned from the printer's own sheet counter. */
    val sheetMs: Long = DEFAULT_SHEET_MS,
) {
    val isRunning: Boolean get() = finished == null

    companion object {
        const val DEFAULT_SHEET_MS = 9_000L
    }
}

/**
 * Runs print jobs in the application's scope so they survive the screen closing, and keeps the process
 * alive with a foreground service while one runs. One job at a time.
 */
class JobManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val router: PrinterRouter,
    private val diagnostics: DiagnosticsLog,
) {
    private val spool = File(context.cacheDir, "spool").apply { mkdirs() }
    private val _state = MutableStateFlow<JobUiState?>(null)
    val state: StateFlow<JobUiState?> = _state.asStateFlow()

    private var job: Job? = null
    private var reloadAnswer: CompletableDeferred<Boolean>? = null
    private var currentPlan: PrintPlan? = null
    private var reverseBacks = false
    private var passIndex = 0

    /** The faces of one pass in the order the printer gets them, as side indices. Mirrors what the engine sends. */
    private fun facesOfPass(index: Int): List<Int?> {
        val plan = currentPlan?.takeIf { it.route == RouteKind.RASTER } ?: return emptyList()
        val pass = plan.passes.getOrNull(index) ?: return emptyList()
        val ordered = if (pass.role == Pass.Role.BACKS && reverseBacks) pass.faces.reversed() else pass.faces
        return ordered.map { it.sideIndex }
    }

    val isBusy: Boolean get() = _state.value?.isRunning == true

    /** Starts printing. Takes its own claim on [document]; the caller keeps theirs. Returns false if a job is already running. */
    fun start(
        document: RefCountedDocument,
        printer: SavedPrinter,
        capabilities: PrinterCapabilities?,
        settings: PrintSettings,
        plan: PrintPlan? = null,
    ): Boolean {
        if (isBusy) return false
        val held = document.retain() ?: return false
        currentPlan = plan
        reverseBacks = settings.manualBacksLastToFirst
        passIndex = 0
        _state.value = JobUiState(document.name, printer.name, faces = facesOfPass(0))
        if (plan != null && plan.route == RouteKind.RASTER) {
            scope.launch(Dispatchers.IO) {
                plan.passes.flatMap { it.faces }.mapNotNull { it.sideIndex }.distinct().forEach { side ->
                    val bitmap = renderSideBitmap(held, plan.layout, side, THUMB_DPI) ?: return@forEach
                    _state.update { it?.copy(thumbs = it.thumbs + (side to bitmap)) }
                }
            }
        }
        ContextCompat.startForegroundService(context, PrintJobService.intent(context))

        val engine = PrintEngine(
            backends = mapOf(DocumentFormat.PWG_RASTER to PwgRasterBackend(), DocumentFormat.PDF to PdfRasterBackend()),
            spoolDir = spool,
            rasterizer = { AndroidSideRasterizer(held) },
            dispatcher = Dispatchers.IO,
        )
        job = scope.launch {
            var route: PrinterRoute? = null
            try {
                route = try {
                    withContext(Dispatchers.IO) { router.open(printer, connectTimeoutMillis = 10_000) }
                } catch (e: java.io.IOException) {
                    diagnostics.log("job: could not open the printer: ${e.message}")
                    publish(JobEvent.Failed(if (printer.isUsb) PrintFailure.UsbProblem(e.message ?: "The printer is not available", e) else PrintFailure.Unreachable(e)))
                    return@launch
                }
                val request = PrintRequest(
                    protocol = route.protocol,
                    document = held.asSource(),
                    settings = settings,
                    capabilities = capabilities,
                    manualDuplex = ManualDuplexOptions(reverseBackOrder = settings.manualBacksLastToFirst),
                    jobName = document.name,
                    awaitReload = {
                        val answer = CompletableDeferred<Boolean>()
                        reloadAnswer = answer
                        answer.await()
                    },
                )
                diagnostics.log("job: started on ${if (printer.isUsb) "usb" else "network"} printer")
                engine.print(request).collect { event -> publish(event) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                publish(JobEvent.Canceled)
                throw e
            } finally {
                runCatching { route?.close() }
                held.close()
                PrintJobService.stop(context)
            }
        }
        return true
    }

    /** The user has put the paper back for the second pass of a manual duplex job. */
    fun confirmReloaded() {
        reloadAnswer?.complete(true)
        reloadAnswer = null
        passIndex++
        _state.update {
            it?.copy(waitingForReload = false, faces = facesOfPass(passIndex), sendFraction = 0f, printStartMs = null, sheetsDone = null, sheetDoneAtMs = null)
        }
    }

    /** Stops the job, including a transfer in progress. */
    fun cancel() {
        reloadAnswer?.complete(false)
        reloadAnswer = null
        job?.cancel()
    }

    /** Forgets a finished job so the UI returns to normal. */
    fun dismiss() {
        if (!isBusy) _state.value = null
    }

    private fun publish(event: JobEvent) {
        when (event) {
            JobEvent.Completed, JobEvent.Canceled, is JobEvent.Failed, is JobEvent.Detached, is JobEvent.Attention, JobEvent.NeedsReload ->
                diagnostics.log("job: ${event::class.simpleName}${(event as? JobEvent.Failed)?.let { " ${it.failure::class.simpleName}" }.orEmpty()}")
            else -> Unit
        }
        _state.update { current ->
            current ?: return@update null
            when (event) {
                JobEvent.Completed, JobEvent.Canceled, is JobEvent.Failed, is JobEvent.Detached -> current.copy(finished = event, waitingForReload = false)
                JobEvent.NeedsReload -> current.copy(waitingForReload = true, latest = event)
                is JobEvent.Preparing -> current.copy(latest = event, currentPage = event.page, totalPages = maxOf(current.totalPages, event.totalPages))
                is JobEvent.Sending -> {
                    val now = System.currentTimeMillis()
                    val fraction = event.totalBytes?.takeIf { it > 0 }?.let { (event.bytesSent.toFloat() / it).coerceIn(0f, 1f) } ?: current.sendFraction
                    current.copy(latest = event, sendFraction = fraction, printStartMs = current.printStartMs ?: now)
                }
                is JobEvent.Printing -> {
                    val now = System.currentTimeMillis()
                    val done = event.sheetsDone
                    if (done != null && done != current.sheetsDone) {
                        val since = current.sheetDoneAtMs ?: current.printStartMs ?: now
                        val delta = ((now - since) / maxOf(1, done - (current.sheetsDone ?: 0))).coerceIn(2_000L, 60_000L)
                        val learned = if (current.sheetsDone == null && done <= 1) current.sheetMs else (current.sheetMs + delta) / 2
                        current.copy(latest = event, sheetsDone = done, sheetDoneAtMs = now, sheetMs = learned, sendFraction = 1f, printStartMs = current.printStartMs ?: now)
                    } else {
                        current.copy(latest = event, sendFraction = 1f, printStartMs = current.printStartMs ?: now)
                    }
                }
                is JobEvent.Accepted -> current.copy(latest = event, sendFraction = 1f, printStartMs = current.printStartMs ?: System.currentTimeMillis())
                else -> current.copy(latest = event)
            }
        }
        PrintJobService.update(context, _state.value)
    }
}

private const val THUMB_DPI = 36
