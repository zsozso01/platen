package io.github.zsozso01.platen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.route.ipp.AddressProbeException
import io.github.zsozso01.platen.transport.network.DiscoveredPrinter
import kotlinx.coroutines.launch

@Composable
fun AddPrinterDialog(discovered: List<DiscoveredPrinter>, onDismiss: () -> Unit, add: suspend (String) -> AddResult) {
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun submit(address: String = text) {
        if (busy || address.isBlank()) return
        busy = true
        error = null
        scope.launch {
            when (val result = add(address)) {
                is AddResult.Added -> onDismiss()
                AddResult.InvalidAddress -> error = R.string.add_invalid
                is AddResult.Failed -> error = when (result.reason) {
                    AddressProbeException.Reason.UNREACHABLE -> R.string.add_error_unreachable
                    AddressProbeException.Reason.NOT_A_PRINTER -> R.string.add_error_not_a_printer
                    AddressProbeException.Reason.ENCRYPTION_REQUIRED -> R.string.add_error_encryption
                    AddressProbeException.Reason.SECURE_NOT_SUPPORTED -> R.string.add_error_secure
                    AddressProbeException.Reason.AUTHENTICATION_REQUIRED -> R.string.add_error_auth
                }
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.add_nearby_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                if (discovered.isEmpty()) {
                    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.add_nearby_searching), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.add_nearby_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                discovered.forEach { printer ->
                    Column(
                        Modifier.fillMaxWidth().clickable(enabled = !busy && !printer.secure) { submit(printer.address) }.padding(vertical = 10.dp),
                    ) {
                        Text(printer.name, style = MaterialTheme.typography.titleMedium, color = if (printer.secure) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (printer.secure) stringResource(R.string.add_nearby_encrypted) else printer.host,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(stringResource(R.string.add_or_enter), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.add_address_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = { Text(stringResource(error ?: R.string.add_address_help)) },
                )
                if (busy) {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.add_testing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy && text.isNotBlank()) { Text(stringResource(R.string.add_button)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}
