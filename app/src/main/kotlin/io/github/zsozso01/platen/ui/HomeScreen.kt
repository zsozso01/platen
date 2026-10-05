package io.github.zsozso01.platen.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.data.SavedPrinter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    printers: List<SavedPrinter>,
    statuses: Map<String, PrinterStatus>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onAddPrinter: () -> Unit,
    onRemove: (SavedPrinter) -> Unit,
    onPickDocument: () -> Unit,
    runningJob: JobBanner?,
    onOpenJob: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            runningJob?.let {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable(onClick = onOpenJob),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(it.title, style = MaterialTheme.typography.titleMedium)
                        Text(it.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (printers.isEmpty()) {
                EmptyState(Modifier.weight(1f), onAddPrinter)
            } else {
                Text(stringResource(R.string.home_printers), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.weight(1f).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(printers, key = { it.id }) { printer ->
                        PrinterCard(printer, statuses[printer.id] ?: PrinterStatus.Checking, selected = printer.id == (selectedId ?: printers.first().id), onSelect = { onSelect(printer.id) }, onRemove = { onRemove(printer) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onAddPrinter, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(R.string.home_add_printer), modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPickDocument, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.home_print_document))
                }
            }
            Text(
                stringResource(R.string.home_privacy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

data class JobBanner(val title: String, val text: String)

@Composable
private fun EmptyState(modifier: Modifier, onAdd: () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.home_no_printers_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_no_printers_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.home_add_printer), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun PrinterCard(printer: SavedPrinter, status: PrinterStatus, selected: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
            Column(Modifier.weight(1f)) {
                Text(printer.name, style = MaterialTheme.typography.titleMedium)
                Text(printer.hostHeader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusLine(status)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.printer_remove))
            }
        }
    }
}

@Composable
private fun StatusLine(status: PrinterStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            PrinterStatus.Checking -> {
                CircularProgressIndicator(modifier = Modifier.height(14.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.printer_checking), style = MaterialTheme.typography.bodySmall)
            }
            PrinterStatus.Unreachable -> Text(stringResource(R.string.printer_unreachable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            is PrinterStatus.Online -> {
                val problem = status.issues.firstOrNull { it.severity != io.github.zsozso01.platen.core.model.Severity.INFO }
                when {
                    problem != null -> Text(issueText(problem), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    status.state == PrinterState.PRINTING -> Text(stringResource(R.string.printer_busy), style = MaterialTheme.typography.bodySmall)
                    status.state == PrinterState.STOPPED -> Text(stringResource(R.string.printer_stopped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    else -> Text(stringResource(R.string.printer_ready), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
