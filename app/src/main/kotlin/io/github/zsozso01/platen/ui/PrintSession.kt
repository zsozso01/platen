package io.github.zsozso01.platen.ui

import android.graphics.Bitmap
import io.github.zsozso01.platen.AppContainer
import io.github.zsozso01.platen.core.engine.ManualDuplexOptions
import io.github.zsozso01.platen.core.engine.Pass
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.PrintPlanner
import io.github.zsozso01.platen.core.engine.PlanningException
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.engine.inSendOrder
import io.github.zsozso01.platen.core.engine.printsBothSides
import io.github.zsozso01.platen.core.layout.LayoutPlan
import io.github.zsozso01.platen.core.layout.LayoutPlanner
import io.github.zsozso01.platen.core.layout.SheetFaces
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
import io.github.zsozso01.platen.job.renderSideBitmap
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
    /** What the preview shows; null while there is nothing to lay out. */
    val sheetPreview: SheetPreview? = null,
    /** Index of the sheet shown in [sheetPreview]. */
    val previewSheet: Int = 0,
    val previewFront: Bitmap? = null,
    /** Null when the back is blank, not two-sided, or still being drawn. */
    val previewBack: Bitmap? = null,
)

/** What the preview shows: the sheets of one copy in print order, with the pages on each face. */
data class SheetPreview(
    val twoSided: Boolean,
    val sheets: List<SheetFaces>,
    /** Pages label of the front and back of each sheet; null = nothing printed on that face. */
    val labels: List<Pair<String?, String?>>,
    /** Manual two-sided only: what each pass sends, in the order the printer gets it. Empty otherwise. */
    val passes: List<PassLabels>,
    /** Sheet width / height. */
    val aspect: Float,
)

/** The faces of one pass as page labels (null = blank), for one copy; [repeatsForCopies] when the pass repeats them per copy. */
data class PassLabels(val role: Pass.Role, val faces: List<String?>, val repeatsForCopies: Boolean)

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
            _state.map { Triple(it.settings, (it.caps as? CapsState.Ready)?.capabilities, it.previewSheet) }
                .distinctUntilChanged()
                .debounce(150)
                .collectLatest { (settings, caps, sheet) -> replan(settings, caps, sheet) }
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
            runCatching { change(s.settings) }.getOrNull()?.let { s.copy(settings = it, previewSheet = s.previewSheet.coerceAtLeast(0)) } ?: s
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

    fun setPreviewSheet(index: Int) {
        _state.update { it.copy(previewSheet = index) }
    }

    /** Starts printing. Returns false if a job is already running or nothing can be planned. */
    fun print(): Boolean {
        val s = _state.value
        val printer = s.printer ?: return false
        val caps = (s.caps as? CapsState.Ready)?.capabilities ?: return false
        if (s.plan !is PlanState.Ready) return false
        return container.jobManager.start(document, printer, caps, s.settings, s.plan.plan)
    }

    fun close() {
        scope.cancel()
        // The preview bitmaps are deliberately not recycled: Compose may still draw them in the current frame,
        // and on API 26+ their pixel memory is reclaimed by the garbage collector anyway.
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

    private suspend fun replan(settings: PrintSettings, caps: PrinterCapabilities?, sheetIndex: Int) {
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
        val shownLayout = layout?.takeUnless { it.isEmpty }
        if (shownLayout == null) {
            // The old preview bitmaps are dropped, not recycled: see [close].
            _state.update { it.copy(plan = plan, layout = layout, sheetPreview = null, previewSheet = 0, previewFront = null, previewBack = null) }
            return
        }

        val readyPlan = (plan as? PlanState.Ready)?.plan
        val twoSided = readyPlan?.printsBothSides ?: (settings.sides.isDuplex || shownLayout.requiresDuplex)
        val sheets = shownLayout.sheets(twoSided)
        val shown = sheetIndex.coerceIn(0, sheets.lastIndex)
        val passes = if (readyPlan != null && readyPlan.needsReload) {
            val options = ManualDuplexOptions(reverseBackOrder = settings.manualBacksLastToFirst)
            readyPlan.passes.map { pass ->
                val faces = pass.inSendOrder(options)
                // A manual two-sided pass holds one face per sheet and copy, so the first sheets.size are one copy.
                PassLabels(pass.role, faces.take(sheets.size).map { shownLayout.pagesLabel(it.sideIndex) }, faces.size > sheets.size)
            }
        } else {
            emptyList()
        }
        val preview = SheetPreview(
            twoSided = twoSided,
            sheets = sheets,
            labels = sheets.map { shownLayout.pagesLabel(it.front) to shownLayout.pagesLabel(it.back) },
            passes = passes,
            aspect = (shownLayout.sheet.widthPoints / shownLayout.sheet.heightPoints).toFloat(),
        )
        val shownFaces = sheets[shown]
        val (front, back) = withContext(Dispatchers.IO) {
            val frontBitmap = renderSideBitmap(document, shownLayout, shownFaces.front, PREVIEW_DPI)
            // A blank back stays null: the preview draws it as an empty outlined sheet.
            val backBitmap = shownFaces.back?.takeIf { twoSided && shownLayout.pagesLabel(it) != null }?.let { renderSideBitmap(document, shownLayout, it, PREVIEW_DPI) }
            frontBitmap to backBitmap
        }
        // The old bitmaps are dropped, not recycled: see [close].
        _state.update { it.copy(plan = plan, layout = layout, sheetPreview = preview, previewSheet = shown, previewFront = front, previewBack = back) }
    }

    private companion object {
        const val PREVIEW_DPI = 60
    }
}
