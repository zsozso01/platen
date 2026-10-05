package io.github.zsozso01.platen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.engine.Decision
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.SettingKind
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.MarginSetting
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PageSelection
import io.github.zsozso01.platen.core.model.PagesPerSheet
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintScreen(
    state: PrintUiState,
    session: PrintSession,
    jobRunning: Boolean,
    onBack: () -> Unit,
    onPrint: () -> Unit,
) {
    val ready = state.caps as? CapsState.Ready
    val plan = (state.plan as? PlanState.Ready)?.plan
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.documentName, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sheetSummary(plan, state), style = MaterialTheme.typography.bodyMedium)
                        state.printer?.let { Text(stringResource(R.string.print_to, it.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Button(onClick = onPrint, enabled = plan != null && !state.pageRangeInvalid && !jobRunning, modifier = Modifier.height(48.dp)) {
                        Text(stringResource(R.string.print_button))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            item { Preview(state, session) }
            item { PrinterSection(state, session) }
            if (ready != null) {
                item { Settings(state, session, ready.capabilities) }
                item { Notes(state, plan) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun sheetSummary(plan: PrintPlan?, state: PrintUiState): String {
    val layout = plan?.layout ?: state.layout ?: return stringResource(R.string.print_loading)
    if (layout.isEmpty) return stringResource(R.string.print_nothing)
    val duplex = layout.requiresDuplex || (state.settings.sides.isDuplex)
    val sheets = layout.sheetCount(duplex) * state.settings.copies.coerceAtLeast(1)
    return if (sheets == 1) stringResource(R.string.print_sheet_one) else stringResource(R.string.print_sheets, sheets)
}

@Composable
private fun Preview(state: PrintUiState, session: PrintSession) {
    val layout = state.layout
    val sides = layout?.sides?.size ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 380.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = state.preview
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 356.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height).background(Color.White),
                )
            } else {
                CircularProgressIndicator()
            }
        }
        if (sides > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { session.setPreviewSide(state.previewSide - 1) }, enabled = state.previewSide > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) }
                Text(stringResource(R.string.print_page_of, state.previewSide + 1, sides), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { session.setPreviewSide(state.previewSide + 1) }, enabled = state.previewSide < sides - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
            }
        }
    }
}

@Composable
private fun PrinterSection(state: PrintUiState, session: PrintSession) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (state.printers.size > 1) {
            DropdownSetting(
                label = stringResource(R.string.print_choose_printer),
                options = state.printers.map { it to it.name },
                selected = state.printer ?: state.printers.first(),
                onSelect = session::selectPrinter,
            )
        }
        when (val caps = state.caps) {
            CapsState.Loading -> Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.print_loading), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            is CapsState.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.print_caps_failed, state.printer?.name.orEmpty()), style = MaterialTheme.typography.titleSmall)
                    Text(caps.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = session::retryCapabilities) { Text(stringResource(R.string.print_retry)) }
                }
            }
            is CapsState.Ready -> {
                val problems = caps.issues.filter { it.severity != io.github.zsozso01.platen.core.model.Severity.INFO }
                problems.forEach { Text(issueText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp)) }
            }
        }
    }
}

@Composable
private fun Settings(state: PrintUiState, session: PrintSession, caps: PrinterCapabilities) {
    val s = state.settings
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(stringResource(R.string.section_basic))
        Stepper(stringResource(R.string.setting_copies), s.copies, 1..99) { n -> session.update { it.copy(copies = n) } }
        if (s.copies > 1) SwitchRow(stringResource(R.string.setting_collate), s.collate, { c -> session.update { it.copy(collate = c) } })

        val colorOptions = buildList {
            add(ColorMode.AUTO to stringResource(R.string.color_auto))
            if (ColorMode.COLOR in caps.colorModes || caps.colorModes.size <= 1) add(ColorMode.COLOR to stringResource(R.string.color_color))
            add(ColorMode.MONOCHROME to stringResource(R.string.color_mono))
        }
        ChipChoice(stringResource(R.string.setting_color), colorOptions, s.color) { c -> session.update { it.copy(color = c) } }

        DropdownSetting(
            stringResource(R.string.setting_sides),
            listOf(Sides.ONE_SIDED to stringResource(R.string.sides_one), Sides.TWO_SIDED_LONG_EDGE to stringResource(R.string.sides_long), Sides.TWO_SIDED_SHORT_EDGE to stringResource(R.string.sides_short)),
            s.sides,
        ) { v -> session.update { it.copy(sides = v) } }

        SectionTitle(stringResource(R.string.setting_pages))
        OutlinedTextField(
            value = state.pageRangeText,
            onValueChange = session::setPageRange,
            label = { Text(stringResource(R.string.pages_all)) },
            placeholder = { Text(stringResource(R.string.pages_hint)) },
            singleLine = true,
            isError = state.pageRangeInvalid,
            supportingText = if (state.pageRangeInvalid) ({ Text(stringResource(R.string.pages_invalid)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        ChipChoice(
            stringResource(R.string.setting_pages),
            listOf(
                PageSelection.Parity.ALL to stringResource(R.string.pages_all),
                PageSelection.Parity.ODD_ONLY to stringResource(R.string.pages_odd),
                PageSelection.Parity.EVEN_ONLY to stringResource(R.string.pages_even),
            ),
            s.pages.parity,
        ) { p -> session.update { it.copy(pages = it.pages.copy(parity = p)) } }
        SwitchRow(stringResource(R.string.pages_reverse), s.reverseOrder, { r -> session.update { it.copy(reverseOrder = r) } })

        SectionTitle(stringResource(R.string.section_layout))
        ScalingControl(s.scaling, onChange = { sc -> session.update { it.copy(scaling = sc) } })
        DropdownSetting(
            stringResource(R.string.setting_orientation),
            listOf(Orientation.AUTO to stringResource(R.string.orientation_auto), Orientation.PORTRAIT to stringResource(R.string.orientation_portrait), Orientation.LANDSCAPE to stringResource(R.string.orientation_landscape)),
            s.orientation,
        ) { o -> session.update { it.copy(orientation = o) } }
        DropdownSetting(
            stringResource(R.string.setting_pages_per_sheet),
            PagesPerSheet.SUPPORTED.sorted().map { it to stringResource(R.string.pages_per_sheet_n, it) },
            s.pagesPerSheet.count,
            enabled = !s.booklet,
        ) { n -> session.update { it.copy(pagesPerSheet = it.pagesPerSheet.copy(count = n)) } }
        if (s.pagesPerSheet.count > 1 && !s.booklet) SwitchRow(stringResource(R.string.pages_per_sheet_border), s.pagesPerSheet.border, { b -> session.update { it.copy(pagesPerSheet = it.pagesPerSheet.copy(border = b)) } })
        if (state.pageCount > 1) SwitchRow(stringResource(R.string.setting_booklet), s.booklet, { b -> session.update { it.copy(booklet = b) } })
        MarginsControl(s.margins, state.customMarginMm, session)

        SectionTitle(stringResource(R.string.section_quality))
        DropdownSetting(
            stringResource(R.string.setting_quality),
            listOf(Quality.DRAFT to stringResource(R.string.quality_draft), Quality.NORMAL to stringResource(R.string.quality_normal), Quality.HIGH to stringResource(R.string.quality_high)),
            s.quality,
        ) { q -> session.update { it.copy(quality = q) } }
        SwitchRow(stringResource(R.string.setting_economy), s.economy, { e -> session.update { it.copy(economy = e) } })

        val sizes = caps.mediaSizes
        if (sizes.isNotEmpty()) {
            val options: List<Pair<MediaSize?, String>> = sizes.map { it to paperLabel(it) }
            val selected = s.paper?.let { chosen -> sizes.firstOrNull { it.samePaperAs(chosen) } } ?: caps.defaultMedia?.let { d -> sizes.firstOrNull { it.samePaperAs(d) } }
            DropdownSetting(stringResource(R.string.setting_paper), options, selected) { m -> session.update { it.copy(paper = m) } }
        }
        if (caps.trays.size > 1) {
            val options: List<Pair<MediaSource?, String>> = listOf<Pair<MediaSource?, String>>(null to stringResource(R.string.tray_auto)) + caps.trays.map { it to it.id }
            DropdownSetting(stringResource(R.string.setting_tray), options, s.tray) { t -> session.update { it.copy(tray = t) } }
        }
    }
}

@Composable
private fun ScalingControl(scaling: Scaling, onChange: (Scaling) -> Unit) {
    var customText by remember { mutableStateOf("100") }
    val kind = when (scaling) {
        Scaling.ShrinkToFit -> 0
        Scaling.FitToPage -> 1
        Scaling.Fill -> 2
        Scaling.ActualSize -> 3
        is Scaling.Custom -> 4
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownSetting(
            stringResource(R.string.setting_scaling),
            listOf(
                0 to stringResource(R.string.scaling_shrink),
                1 to stringResource(R.string.scaling_fit),
                2 to stringResource(R.string.scaling_fill),
                3 to stringResource(R.string.scaling_actual),
                4 to stringResource(R.string.scaling_custom),
            ),
            kind,
        ) { k ->
            onChange(
                when (k) {
                    0 -> Scaling.ShrinkToFit
                    1 -> Scaling.FitToPage
                    2 -> Scaling.Fill
                    3 -> Scaling.ActualSize
                    else -> Scaling.Custom(customText.toIntOrNull()?.coerceIn(1, 1000) ?: 100)
                },
            )
        }
        if (kind == 4) {
            OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    customText = text.filter(Char::isDigit).take(4)
                    customText.toIntOrNull()?.takeIf { it in 1..1000 }?.let { onChange(Scaling.Custom(it)) }
                },
                label = { Text(stringResource(R.string.scaling_percent)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MarginsControl(margins: MarginSetting, customMm: String, session: PrintSession) {
    val kind = when (margins) {
        MarginSetting.PrinterDefault -> 0
        MarginSetting.None -> 1
        is MarginSetting.Custom -> 2
    }
    fun custom(mm: String): MarginSetting = MarginSetting.Custom(Margins.uniform(((mm.toDoubleOrNull() ?: 10.0).coerceIn(0.0, 100.0) * 100).toInt()))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownSetting(
            stringResource(R.string.setting_margins),
            listOf(0 to stringResource(R.string.margins_default), 1 to stringResource(R.string.margins_none), 2 to stringResource(R.string.margins_custom)),
            kind,
        ) { k -> session.update { it.copy(margins = when (k) { 0 -> MarginSetting.PrinterDefault; 1 -> MarginSetting.None; else -> custom(customMm) }) } }
        if (kind == 2) {
            OutlinedTextField(
                value = customMm,
                onValueChange = { text ->
                    val cleaned = text.filter { it.isDigit() || it == '.' }.take(5)
                    session.setCustomMargin(cleaned)
                    session.update { it.copy(margins = custom(cleaned)) }
                },
                label = { Text(stringResource(R.string.margins_mm)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun settingLabel(kind: SettingKind): String = stringResource(
    when (kind) {
        SettingKind.COPIES, SettingKind.COLLATION -> R.string.setting_copies
        SettingKind.SIDES -> R.string.setting_sides
        SettingKind.PAGES, SettingKind.REVERSE -> R.string.setting_pages
        SettingKind.PAPER -> R.string.setting_paper
        SettingKind.PAPER_TYPE, SettingKind.TRAY, SettingKind.OUTPUT_BIN -> R.string.setting_tray
        SettingKind.COLOR -> R.string.setting_color
        SettingKind.QUALITY, SettingKind.RESOLUTION -> R.string.setting_quality
        SettingKind.ECONOMY -> R.string.setting_economy
        SettingKind.SCALING -> R.string.setting_scaling
        SettingKind.ORIENTATION -> R.string.setting_orientation
        SettingKind.MARGINS -> R.string.setting_margins
        SettingKind.PAGES_PER_SHEET -> R.string.setting_pages_per_sheet
        SettingKind.BOOKLET -> R.string.setting_booklet
    },
)

@Composable
private fun Notes(state: PrintUiState, plan: PrintPlan?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.plan is PlanState.Impossible) {
            Text(stringResource(R.string.print_cannot_plan, state.plan.reason), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }
        if (plan == null) return
        val notable = plan.decisions.filter { it.outcome != Decision.Outcome.BY_PRINTER || it.detail != null }
            .filter { it.outcome != Decision.Outcome.BY_APP || it.detail != null || it.setting == SettingKind.SIDES }
        if (notable.isNotEmpty() || plan.needsReload) {
            SectionTitle(stringResource(R.string.section_notes))
            notable.forEach { d ->
                val warning = d.outcome == Decision.Outcome.IGNORED || d.outcome == Decision.Outcome.APPROXIMATED
                Row(verticalAlignment = Alignment.Top) {
                    if (warning) Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(
                        text = "${settingLabel(d.setting)}: ${d.detail ?: stringResource(R.string.note_by_app)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { open = !open }) { Text(stringResource(R.string.section_details)) }
        if (open) {
            val lines = buildList {
                add("route: ${plan.route}  format: ${plan.format.mime}")
                plan.raster?.let { add("raster: ${it.dpi} dpi ${it.pwgType} duplex=${it.printerDuplex} sheetBack=${it.sheetBack}") }
                add("sheet: ${plan.layout.sheet.pwgName ?: "${plan.layout.sheet.widthMm}x${plan.layout.sheet.heightMm} mm"}  sides: ${plan.layout.sides.size}")
                add("passes: ${plan.passes.map { "${it.role}:${it.faces.size}" }}")
                add("printer settings: ${plan.printer}")
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
