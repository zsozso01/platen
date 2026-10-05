package io.github.zsozso01.platen.ui

import android.graphics.Bitmap
import io.github.zsozso01.platen.AppContainer
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.PrintPlanner
import io.github.zsozso01.platen.core.engine.PlanningException
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.layout.LayoutPlan
import io.github.zsozso01.platen.core.layout.LayoutPlanner
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PrintSettings
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.data.SavedPrinter
import io.github.zsozso01.platen.job.RefCountedDocument
import io.github.zsozso01.platen.job.asSource
import io.github.zsozso01.platen.platform.render.AndroidSideRasterizer
import io.github.zsozso01.platen.platform.render.ImageDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CapsState {
    data object Loading : CapsState
    data class Ready(val capabilities: PrinterCapabilities, val state: PrinterState, val issues: List<PrinterIssue>, val makeAndModel: String?) : CapsState
    data class Failed(val message: String) : CapsState
}

sealed interface PlanState {
    data object None : PlanState
    data class Ready(val plan: PrintPlan) : PlanState
    data class Impossible(val reason: String) : PlanState
}

data class PrintUiState(
    val documentName: String,
    val pageCount: Int,
    val isImage: Boolean,
    val printers: List<SavedPrinter>,
    val printer: SavedPrinter?,
    val caps: CapsState = CapsState.Loading,
    val settings: PrintSettings = PrintSettings(),
    val pageRangeText: String = "",
    val pageRangeInvalid: Boolean = false,
    val customMarginMm: String = "10",
    val plan: PlanState = PlanState.None,
    val layout: LayoutPlan? = null,
    val previewSide: Int = 0,
    val preview: Bitmap? = null,
)

/**
 * One document being prepared for printing: the chosen printer's capabilities, the user's settings, the
 * resulting plan and a live preview. Owns a claim on the document and releases it in [close].
 */
@OptIn(FlowPreview::class)
class PrintSession(
    private val container: AppContainer,
    val document: RefCountedDocument,
    printers: List<SavedPrinter>,
    initialPrinter: SavedPrinter?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(
        PrintUiState(
            documentName = document.name,
            pageCount = document.pageCount,
            isImage = document.pdf == null,
            printers = printers,
            printer = initialPrinter ?: printers.firstOrNull(),
            // A photo should fill the sheet; a document should only be shrunk when too big.
            settings = PrintSettings(scaling = if (document.pdf == null) Scaling.FitToPage else Scaling.ShrinkToFit),
        ),
    )
    val state: StateFlow<PrintUiState> = _state.asStateFlow()

    init {
        _state.value.printer?.let(::loadCapabilities)
        scope.launch {
            _state.map { Triple(it.settings, (it.caps as? CapsState.Ready)?.capabilities, it.previewSide) }
                .distinctUntilChanged()
                .debounce(150)
                .collectLatest { (settings, caps, side) -> replan(settings, caps, side) }
        }
    }

    // --- inputs ---------------------------------------------------------------------------------

    fun selectPrinter(printer: SavedPrinter) {
        if (printer.id == _state.value.printer?.id) return
        _state.update { it.copy(printer = printer, caps = CapsState.Loading, plan = PlanState.None) }
        loadCapabilities(printer)
    }

    fun retryCapabilities() {
        _state.value.printer?.let {
            _state.update { s -> s.copy(caps = CapsState.Loading) }
            loadCapabilities(it)
        }
    }

    fun update(change: (PrintSettings) -> PrintSettings) {
        _state.update { s ->
            runCatching { change(s.settings) }.getOrNull()?.let { s.copy(settings = it, previewSide = s.previewSide.coerceAtLeast(0)) } ?: s
        }
    }

    fun setPageRange(text: String) {
        val parsed = PageSelection.parse(text, document.pageCount)
        _state.update { s ->
            if (parsed == null) {
                s.copy(pageRangeText = text, pageRangeInvalid = true)
            } else {
                s.copy(pageRangeText = text, pageRangeInvalid = false, settings = s.settings.copy(pages = parsed.copy(parity = s.settings.pages.parity)))
            }
        }
    }

    fun setCustomMargin(text: String) {
        _state.update { it.copy(customMarginMm = text) }
    }

    fun setPreviewSide(index: Int) {
        _state.update { it.copy(previewSide = index) }
    }

    /** Starts printing. Returns false if a job is already running or nothing can be planned. */
    fun print(): Boolean {
        val s = _state.value
        val printer = s.printer ?: return false
        val caps = (s.caps as? CapsState.Ready)?.capabilities ?: return false
        if (s.plan !is PlanState.Ready) return false
        return container.jobManager.start(document, printer, caps, s.settings)
    }

    fun close() {
        scope.cancel()
        _state.value.preview?.recycle()
        document.close()
    }

    // --- work -------------------------------------------------------------------------------------

    private fun loadCapabilities(printer: SavedPrinter) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    container.printerRouter.openAllowingPrompt(printer, connectTimeoutMillis = 4_000).use { it.protocol.probe() }
                }
            }
            if (_state.value.printer?.id != printer.id) return@launch // the user picked another one meanwhile
            _state.update {
                it.copy(
                    caps = result.fold(
                        onSuccess = { p -> CapsState.Ready(p.capabilities, p.state, p.issues, p.makeAndModel) },
                        onFailure = { e -> CapsState.Failed(e.message ?: e.javaClass.simpleName) },
                    ),
                )
            }
        }
    }

    private suspend fun replan(settings: PrintSettings, caps: PrinterCapabilities?, side: Int) {
        val source = document.asSource()
        var plan: PlanState = PlanState.None
        var layout: LayoutPlan? = null
        if (caps != null) {
            try {
                val p = PrintPlanner.plan(source, settings, caps)
                plan = PlanState.Ready(p)
                layout = p.layout
            } catch (e: PlanningException) {
                plan = PlanState.Impossible(e.message ?: "")
            }
        }
        if (layout == null) {
            // No plan (printer unknown, or the plan failed): still show the layout on a default sheet.
            layout = runCatching {
                LayoutPlanner.plan(List(document.pageCount) { document.pageGeometry(it) }, settings, MediaSize.A4)
            }.getOrNull()?.takeUnless { it.isEmpty }
        }
        val shownSide = side.coerceIn(0, ((layout?.sides?.size ?: 1) - 1).coerceAtLeast(0))
        val bitmap = layout?.takeUnless { it.isEmpty }?.let { renderPreview(it, shownSide) }
        _state.update { current ->
            if (current.preview != null && current.preview !== bitmap) current.preview.recycle()
            current.copy(plan = plan, layout = layout, previewSide = shownSide, preview = bitmap)
        }
    }

    private suspend fun renderPreview(layout: LayoutPlan, sideIndex: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val dpi = PREVIEW_DPI
            val rasterizer = AndroidSideRasterizer(document, bandRows = 128)
            try {
                val side = rasterizer.rasterize(layout.sides[sideIndex], layout.sheet, dpi, RasterPixelFormat.RGB24)
                val width = side.widthPx
                val height = side.heightPx
                val rows = ByteArray(width * height * 3)
                side.readRows(0, height, rows)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    pixels[i] = 0xFF shl 24 or ((rows[i * 3].toInt() and 0xFF) shl 16) or ((rows[i * 3 + 1].toInt() and 0xFF) shl 8) or (rows[i * 3 + 2].toInt() and 0xFF)
                }
                Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } finally {
                rasterizer.close()
            }
        }.getOrNull()
    }

    private companion object {
        const val PREVIEW_DPI = 60
    }
}
