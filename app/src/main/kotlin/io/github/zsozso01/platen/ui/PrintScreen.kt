package io.github.zsozso01.platen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.engine.Decision
import io.github.zsozso01.platen.core.engine.ManualDuplexOptions
import io.github.zsozso01.platen.core.engine.Pass
import io.github.zsozso01.platen.core.engine.PrintPlan
import io.github.zsozso01.platen.core.engine.SettingKind
import io.github.zsozso01.platen.core.engine.printsBothSides
import io.github.zsozso01.platen.core.engine.reloadGuide
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
                item { Settings(state, session, ready.capabilities, plan) }
                item { Notes(state, plan) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun sheetSummary(plan: PrintPlan?, state: PrintUiState): String {
    // The same sheet list the preview pages through, so the bottom bar and the pager always agree.
    val preview = state.sheetPreview
    if (preview == null) {
        val layout = plan?.layout ?: state.layout ?: return stringResource(R.string.print_loading)
        return stringResource(if (layout.isEmpty) R.string.print_nothing else R.string.print_loading)
    }
    val perCopy = preview.sheets.size
    val copies = state.settings.copies.coerceAtLeast(1)
    val total = perCopy * copies
    return when {
        copies > 1 -> stringResource(R.string.print_sheets_copies, total, copies, perCopy)
        total == 1 -> stringResource(R.string.print_sheet_one)
        else -> stringResource(R.string.print_sheets, total)
    }
}

/** The sheets of one copy as they will come out: front and back side by side, a pager, an overview and, for manual two-sided, the two passes. */
@Composable
private fun Preview(state: PrintUiState, session: PrintSession) {
    val preview = state.sheetPreview
    val front = state.previewFront
    val back = state.previewBack
    val current = state.previewSheet
    val sheetNo = current + 1
    val count = preview?.sheets?.size ?: 0
    val labels = preview?.labels?.getOrNull(current)
    val manual = (state.plan as? PlanState.Ready)?.plan?.needsReload == true
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 380.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (preview == null || front == null) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (!preview.twoSided) {
                Image(
                    bitmap = remember(front) { front.asImageBitmap() },
                    contentDescription = stringResource(R.string.preview_front_desc, sheetNo, labels?.first.orEmpty()),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 356.dp).aspectRatio(preview.aspect).background(Color.White),
                )
            } else {
                Row(Modifier.widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.preview_front), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        Image(
                            bitmap = remember(front) { front.asImageBitmap() },
                            contentDescription = stringResource(R.string.preview_front_desc, sheetNo, labels?.first.orEmpty()),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(preview.aspect).background(Color.White),
                        )
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.preview_back), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        val face = Modifier.fillMaxWidth().aspectRatio(preview.aspect)
                        val backPages = labels?.second
                        if (backPages == null) {
                            val blankDescription = stringResource(R.string.preview_blank_desc, sheetNo)
                            Box(
                                face.background(Color.White).border(1.dp, MaterialTheme.colorScheme.outlineVariant).clearAndSetSemantics { contentDescription = blankDescription },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(R.string.preview_blank), style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                            }
                        } else if (back == null) {
                            // Printed, but the picture is still being drawn.
                            Box(face.background(Color.White), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            Image(
                                bitmap = remember(back) { back.asImageBitmap() },
                                contentDescription = stringResource(R.string.preview_back_desc, sheetNo, backPages),
                                contentScale = ContentScale.Fit,
                                modifier = face.background(Color.White),
                            )
                        }
                    }
                }
            }
        }
        if (count > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { session.setPreviewSheet(current - 1) }, enabled = current > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.preview_prev)) }
                Text(stringResource(R.string.print_page_of, sheetNo, count), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { session.setPreviewSheet(current + 1) }, enabled = current < count - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.preview_next)) }
            }
        }
        if (preview != null && preview.twoSided && manual && labels != null && labels.second == null) {
            Text(
                stringResource(R.string.preview_blank_back_manual),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (preview != null && count > 1) SheetOverview(preview, current, session::setPreviewSheet)
        if (preview != null && preview.passes.isNotEmpty()) PassLines(preview.passes)
    }
}

/** One selectable chip per sheet, "front | back", scrolled to keep the shown sheet in view. */
@Composable
private fun SheetOverview(preview: SheetPreview, selected: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { if (selected in preview.labels.indices) listState.animateScrollToItem(selected) }
    Text(stringResource(R.string.preview_overview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    LazyRow(Modifier.fillMaxWidth(), state = listState, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
        itemsIndexed(preview.labels) { i, (frontPages, backPages) ->
            val text = if (preview.twoSided) "${frontPages ?: "–"} | ${backPages ?: "–"}" else (frontPages ?: "–")
            FilterChip(selected = i == selected, onClick = { onSelect(i) }, label = { Text(text) })
        }
    }
}

/** Manual two-sided only: the pages of each pass in the order the printer gets them. */
@Composable
private fun PassLines(passes: List<PassLabels>) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        passes.forEach { pass ->
            val blank = stringResource(R.string.preview_pass_blank)
            // Labels such as "1–4" or "1–3, 7" already hold dashes or commas, so they need a different separator.
            val separator = if (pass.faces.all { it == null || ('–' !in it && ',' !in it) }) ", " else " · "
            val list = pass.faces.joinToString(separator) { it ?: blank }
            val line = when (pass.role) {
                Pass.Role.FRONTS -> stringResource(R.string.preview_pass_fronts, list)
                Pass.Role.BACKS -> stringResource(R.string.preview_pass_backs, list)
                Pass.Role.ALL -> list
            }
            val text = if (pass.repeatsForCopies) "$line ${stringResource(R.string.preview_pass_copies)}" else line
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
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
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
private fun Settings(state: PrintUiState, session: PrintSession, caps: PrinterCapabilities, plan: PrintPlan?) {
    val s = state.settings
    Column(Modifier.fillMaxWidth()) {
        SettingsCard(stringResource(R.string.section_basic), stringResource(R.string.section_basic_hint)) {
            Stepper(stringResource(R.string.setting_copies), s.copies, 1..99) { n -> session.update { it.copy(copies = n) } }
            if (s.copies > 1) {
                SwitchRow(stringResource(R.string.setting_collate), s.collate, { c -> session.update { it.copy(collate = c) } }, supporting = stringResource(R.string.setting_collate_hint))
                CollateArt(s.collate, Modifier.height(88.dp).align(Alignment.CenterHorizontally))
            }

            val colorOptions = buildList {
                add(ColorMode.AUTO to stringResource(R.string.color_auto))
                if (ColorMode.COLOR in caps.colorModes || caps.colorModes.size <= 1) add(ColorMode.COLOR to stringResource(R.string.color_color))
                add(ColorMode.MONOCHROME to stringResource(R.string.color_mono))
            }
            ChipChoice(stringResource(R.string.setting_color), colorOptions, s.color, hint = stringResource(R.string.setting_color_hint)) { c -> session.update { it.copy(color = c) } }
        }

        SettingsCard(stringResource(R.string.section_sides), stringResource(R.string.section_sides_hint)) {
            // A booklet always prints on both sides, whatever the dropdown was set to before.
            val sides = if (s.booklet) plan?.layout?.requiredSides ?: Sides.TWO_SIDED_SHORT_EDGE else s.sides
            if (sides != Sides.ONE_SIDED) SidesArt(sides == Sides.TWO_SIDED_LONG_EDGE, Modifier.height(104.dp).align(Alignment.CenterHorizontally))
            DropdownSetting(
                stringResource(R.string.setting_sides),
                listOf(Sides.ONE_SIDED to stringResource(R.string.sides_one), Sides.TWO_SIDED_LONG_EDGE to stringResource(R.string.sides_long), Sides.TWO_SIDED_SHORT_EDGE to stringResource(R.string.sides_short)),
                sides,
                enabled = !s.booklet,
                hint = stringResource(
                    when {
                        s.booklet -> R.string.sides_booklet_hint
                        s.sides == Sides.ONE_SIDED -> R.string.sides_one_hint
                        s.sides == Sides.TWO_SIDED_LONG_EDGE -> R.string.sides_long_hint
                        else -> R.string.sides_short_hint
                    },
                ),
            ) { v -> session.update { it.copy(sides = v) } }
            SidesHelp(plan, sides, s.manualBacksLastToFirst) { r -> session.update { it.copy(manualBacksLastToFirst = r) } }
        }

        SettingsCard(stringResource(R.string.setting_pages), stringResource(R.string.section_pages_hint)) {
            OutlinedTextField(
                value = state.pageRangeText,
                onValueChange = session::setPageRange,
                label = { Text(stringResource(R.string.pages_range_label)) },
                placeholder = { Text(stringResource(R.string.pages_hint)) },
                singleLine = true,
                isError = state.pageRangeInvalid,
                supportingText = { Text(stringResource(if (state.pageRangeInvalid) R.string.pages_invalid else R.string.pages_range_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            ChipChoice(
                stringResource(R.string.pages_only),
                listOf(
                    PageSelection.Parity.ALL to stringResource(R.string.pages_all),
                    PageSelection.Parity.ODD_ONLY to stringResource(R.string.pages_odd),
                    PageSelection.Parity.EVEN_ONLY to stringResource(R.string.pages_even),
                ),
                s.pages.parity,
                hint = stringResource(R.string.pages_only_hint),
            ) { p -> session.update { it.copy(pages = it.pages.copy(parity = p)) } }
            SwitchRow(stringResource(R.string.pages_reverse), s.reverseOrder, { r -> session.update { it.copy(reverseOrder = r) } }, supporting = stringResource(R.string.pages_reverse_hint))
        }

        SettingsCard(stringResource(R.string.section_layout), stringResource(R.string.section_layout_hint)) {
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
                hint = stringResource(R.string.pages_per_sheet_hint),
            ) { n -> session.update { it.copy(pagesPerSheet = it.pagesPerSheet.copy(count = n)) } }
            if (s.pagesPerSheet.count > 1 && !s.booklet) PagesPerSheetArt(s.pagesPerSheet.count, Modifier.height(100.dp).align(Alignment.CenterHorizontally))
            if (s.pagesPerSheet.count > 1 && !s.booklet) SwitchRow(stringResource(R.string.pages_per_sheet_border), s.pagesPerSheet.border, { b -> session.update { it.copy(pagesPerSheet = it.pagesPerSheet.copy(border = b)) } })
            if (state.pageCount > 1) SwitchRow(stringResource(R.string.setting_booklet), s.booklet, { b -> session.update { it.copy(booklet = b) } }, supporting = stringResource(R.string.setting_booklet_hint))
            MarginsControl(s.margins, state.customMarginMm, session)
        }

        SettingsCard(stringResource(R.string.section_quality), stringResource(R.string.section_quality_hint)) {
            DropdownSetting(
                stringResource(R.string.setting_quality),
                listOf(Quality.DRAFT to stringResource(R.string.quality_draft), Quality.NORMAL to stringResource(R.string.quality_normal), Quality.HIGH to stringResource(R.string.quality_high)),
                s.quality,
            ) { q -> session.update { it.copy(quality = q) } }
            SwitchRow(stringResource(R.string.setting_economy), s.economy, { e -> session.update { it.copy(economy = e) } }, supporting = stringResource(R.string.setting_economy_hint))

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
}

/**
 * Under the Two-sided dropdown: how the manual turn-over works with the real page numbers of this job, the
 * rarely needed "wrong sheets" switch, or a line saying the printer does it all itself.
 */
@Composable
private fun SidesHelp(plan: PrintPlan?, sides: Sides, backsLastToFirst: Boolean, onBacksLastToFirst: (Boolean) -> Unit) {
    // Declared before any early exit so the answer survives the plan being recomputed.
    var open by rememberSaveable { mutableStateOf(backsLastToFirst) }
    if (plan == null) return
    if (plan.needsReload) {
        val guide = remember(plan, backsLastToFirst) { plan.reloadGuide(ManualDuplexOptions(reverseBackOrder = backsLastToFirst)) }
        // The mix-up text says "with this off", so its example always comes from the default order.
        val mixUp = remember(plan) { plan.reloadGuide()?.mixUpExample }
        val longEdge = (guide?.binding ?: plan.manualDuplex?.binding ?: sides) != Sides.TWO_SIDED_SHORT_EDGE
        val layout = plan.layout
        Text(stringResource(R.string.flip_intro), style = MaterialTheme.typography.bodyMedium)
        if (guide != null) {
            val printed = guide.firstPrinted
            FlipSteps(
                longEdge = longEdge,
                firstFront = layout.pagesLabel(guide.firstFront).orEmpty(),
                firstBack = layout.pagesLabel(printed?.back),
                firstBackFront = layout.pagesLabel(printed?.front),
            )
            guide.blankBackFronts.firstOrNull()?.let { layout.pagesLabel(it) }?.let { pages ->
                Text(stringResource(R.string.flip_odd_note, pages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sides_trouble_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
            if (open) {
                val back = layout.pagesLabel(mixUp?.back)
                val other = layout.pagesLabel(mixUp?.otherEndFront)
                val front = layout.pagesLabel(mixUp?.front)
                Text(
                    if (back != null && other != null && front != null) stringResource(R.string.sides_trouble_body, back, other, front) else stringResource(R.string.sides_trouble_body_generic),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(if (longEdge) R.string.sides_trouble_upside_down_long else R.string.sides_trouble_upside_down_short), style = MaterialTheme.typography.bodyMedium)
                SwitchRow(stringResource(R.string.sides_backs_reversed), backsLastToFirst, onBacksLastToFirst, supporting = stringResource(R.string.sides_backs_reversed_hint))
            }
        }
    } else if (plan.printsBothSides) {
        Text(stringResource(R.string.sides_by_printer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SettingKind.SIDES -> R.string.section_sides
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
        if (notable.isNotEmpty() || plan.needsReload) SettingsCard(stringResource(R.string.section_notes), stringResource(R.string.section_notes_hint)) {
            notable.forEach { d ->
                val warning = d.outcome == Decision.Outcome.IGNORED || d.outcome == Decision.Outcome.APPROXIMATED
                // The engine's own wording for manual two-sided printing is English only; the app has its own.
                val text = if (d.setting == SettingKind.SIDES && d.outcome == Decision.Outcome.BY_APP) stringResource(R.string.note_manual_duplex) else d.detail ?: stringResource(R.string.note_by_app)
                Row(verticalAlignment = Alignment.Top) {
                    if (warning) Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(
                        text = "${settingLabel(d.setting)}: $text",
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
