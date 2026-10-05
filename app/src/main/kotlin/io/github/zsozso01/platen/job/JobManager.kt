package io.github.zsozso01.platen.job

import android.content.Context
import androidx.core.content.ContextCompat
import io.github.zsozso01.platen.backend.pdf.PdfRasterBackend
import io.github.zsozso01.platen.backend.raster.PwgRasterBackend
import io.github.zsozso01.platen.core.engine.PrintEngine
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
) {
    val isRunning: Boolean get() = finished == null
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

    val isBusy: Boolean get() = _state.value?.isRunning == true

    /** Starts printing. Takes its own claim on [document]; the caller keeps theirs. Returns false if a job is already running. */
    fun start(
        document: RefCountedDocument,
        printer: SavedPrinter,
        capabilities: PrinterCapabilities?,
        settings: PrintSettings,
    ): Boolean {
        if (isBusy) return false
        val held = document.retain() ?: return false
        _state.value = JobUiState(document.name, printer.name)
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
        _state.update { it?.copy(waitingForReload = false) }
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
                else -> current.copy(latest = event)
            }
        }
        PrintJobService.update(context, _state.value)
    }
}
