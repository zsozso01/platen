package io.github.zsozso01.platen.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.job.JobUiState

/** One-line status for a running job, also used by the home screen banner. */
@Composable
fun jobStatusText(job: JobUiState): String = when (val e = job.latest) {
    is JobEvent.Preparing -> stringResource(R.string.job_preparing, e.page, e.totalPages)
    is JobEvent.Sending -> if (e.totalBytes != null && e.totalBytes!! > 0) stringResource(R.string.job_sending_percent, (e.bytesSent * 100 / e.totalBytes!!).toInt()) else stringResource(R.string.job_sending)
    is JobEvent.Accepted -> stringResource(R.string.job_accepted)
    is JobEvent.Printing -> e.sheetsDone?.let { stringResource(R.string.job_printing_sheets, it) } ?: stringResource(R.string.job_printing)
    is JobEvent.Attention -> e.issues.firstOrNull()?.let { issueText(it) } ?: stringResource(R.string.job_attention_title)
    JobEvent.NeedsReload -> stringResource(R.string.job_reload_title)
    else -> stringResource(R.string.job_starting)
}

@Composable
fun JobDialog(job: JobUiState, onCancel: () -> Unit, onReloaded: () -> Unit, onHide: () -> Unit, onDone: () -> Unit, onShareDiagnostics: () -> Unit) {
    val finished = job.finished
    when {
        job.waitingForReload -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.job_reload_title)) },
            text = { Text(stringResource(R.string.job_reload_body)) },
            confirmButton = { TextButton(onClick = onReloaded) { Text(stringResource(R.string.job_reload_continue)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.job_cancel)) } },
        )
        finished != null -> AlertDialog(
            onDismissRequest = onDone,
            title = {
                Text(
                    when (finished) {
                        JobEvent.Completed -> stringResource(R.string.job_done_title)
                        is JobEvent.Failed -> stringResource(R.string.job_failed_title)
                        else -> job.documentName
                    },
                )
            },
            text = {
                Text(
                    when (finished) {
                        JobEvent.Completed -> stringResource(R.string.job_done_body)
                        JobEvent.Canceled -> stringResource(R.string.job_canceled)
                        is JobEvent.Detached -> stringResource(R.string.job_detached) + "\n\n" + finished.reason
                        is JobEvent.Failed -> failureText(finished.failure)
                        else -> ""
                    },
                )
            },
            confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.done)) } },
            dismissButton = if (finished is JobEvent.Failed || finished is JobEvent.Detached) {
                { TextButton(onClick = onShareDiagnostics) { Text(stringResource(R.string.diagnostics_share)) } }
            } else {
                null
            },
        )
        else -> AlertDialog(
            onDismissRequest = onHide,
            title = { Text(job.documentName, maxLines = 1) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    val latest = job.latest
                    val attention = latest as? JobEvent.Attention
                    Text(
                        jobStatusText(job),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (attention != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    val progress = when (latest) {
                        is JobEvent.Sending -> latest.totalBytes?.takeIf { it > 0 }?.let { latest.bytesSent.toFloat() / it }
                        is JobEvent.Preparing -> if (latest.totalPages > 0) (latest.page - 1f) / latest.totalPages else null
                        else -> null
                    }
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = onHide) { Text(stringResource(R.string.job_hide)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.job_cancel)) } },
        )
    }
}
