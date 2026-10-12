package io.github.zsozso01.platen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
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

/**
 * The running job as a card over the bottom of the screen. It never blocks: the user can keep browsing,
 * change settings or open another document while it works. Paper reload, failure and the final result live
 * on the same card instead of in modal dialogs.
 */
@Composable
fun JobCard(job: JobUiState, onCancel: () -> Unit, onReloaded: () -> Unit, onDone: () -> Unit, onShareDiagnostics: () -> Unit, modifier: Modifier = Modifier) {
    val finished = job.finished
    val latest = job.latest
    val phase = when {
        finished == JobEvent.Completed -> JobPhase.DONE
        finished != null && finished != JobEvent.Canceled -> JobPhase.FAILED
        job.waitingForReload || latest is JobEvent.Attention -> JobPhase.WAITING
        latest is JobEvent.Printing || latest is JobEvent.Accepted -> JobPhase.PRINTING
        else -> JobPhase.WORKING
    }
    val position = printPosition(job, phase)
    ElevatedCard(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (position != null) {
                    PrintingArt(job.thumbs[job.faces.getOrNull(position.face)], position.fraction, Modifier.width(84.dp))
                } else {
                    PrinterArt(phase, Modifier.width(76.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(job.documentName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        when {
                            job.waitingForReload -> stringResource(R.string.job_reload_title)
                            finished == JobEvent.Completed -> stringResource(R.string.job_done_title)
                            finished == JobEvent.Canceled -> stringResource(R.string.job_canceled)
                            finished is JobEvent.Failed -> stringResource(R.string.job_failed_title)
                            finished is JobEvent.Detached -> stringResource(R.string.job_detached_title)
                            position != null && position.stage == PrintStage.PRINTING -> stringResource(R.string.job_sheet_of, position.face + 1, job.faces.size)
                            else -> jobStatusText(job)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (phase == JobPhase.FAILED || latest is JobEvent.Attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (finished == null && !job.waitingForReload) {
                        val progress = when (latest) {
                            is JobEvent.Sending -> latest.totalBytes?.takeIf { it > 0 }?.let { latest.bytesSent.toFloat() / it }
                            is JobEvent.Preparing -> if (latest.totalPages > 0) (latest.page - 1f) / latest.totalPages else null
                            else -> null
                        }
                        val modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = modifier) else LinearProgressIndicator(modifier = modifier)
                    }
                }
            }
            when {
                job.waitingForReload -> {
                    Text(stringResource(R.string.job_reload_body), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.job_cancel)) }
                        Button(onClick = onReloaded) { Text(stringResource(R.string.job_reload_continue)) }
                    }
                }
                finished != null -> {
                    val detail = when (finished) {
                        is JobEvent.Detached -> stringResource(R.string.job_detached) + "\n\n" + finished.reason
                        is JobEvent.Failed -> failureText(finished.failure)
                        JobEvent.Completed -> stringResource(R.string.job_done_body)
                        else -> null
                    }
                    if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (finished is JobEvent.Failed || finished is JobEvent.Detached) TextButton(onClick = onShareDiagnostics) { Text(stringResource(R.string.diagnostics_share)) }
                        TextButton(onClick = onDone) { Text(stringResource(R.string.done)) }
                    }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.job_cancel)) }
                }
            }
        }
    }
}

enum class PrintStage { PREPARING, PRINTING }

/** Where the printer is, as a sheet number and how far down that sheet the ink has got. */
data class PrintPosition(val face: Int, val fraction: Float, val stage: PrintStage)

/**
 * Estimates what the printer is doing right now. A printer reports little, so this combines what is known:
 * how much of the job it has received, the sheet counter when it reports one, and how long a sheet has taken so
 * far. The printer cannot be ahead of the data it has, so the estimate is capped by what was sent.
 * Returns null when the pages of the job are unknown (a PDF the printer prints itself).
 */
@Composable
fun printPosition(job: JobUiState, phase: JobPhase): PrintPosition? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = job.finished == null
    LaunchedEffect(running) {
        while (running) {
            withFrameMillis { now = System.currentTimeMillis() }
        }
    }
    val n = job.faces.size
    if (n == 0 || phase == JobPhase.WAITING || phase == JobPhase.FAILED || job.finished != null && job.finished != JobEvent.Completed) return null
    if (job.finished == JobEvent.Completed) return PrintPosition(n - 1, 1f, PrintStage.PRINTING)
    val start = job.printStartMs ?: return PrintPosition(0, 0f, PrintStage.PREPARING)
    val sheetMs = job.sheetMs.coerceAtLeast(1_000L).toFloat()
    val estimated = job.sheetsDone?.let { done -> done + ((now - (job.sheetDoneAtMs ?: start)) / sheetMs).coerceIn(0f, 0.95f) }
        ?: ((now - start) / sheetMs)
    val received = job.sendFraction * n
    val position = minOf(estimated, received, n - 0.02f).coerceAtLeast(0f)
    val face = position.toInt().coerceAtMost(n - 1)
    return PrintPosition(face, position - face, PrintStage.PRINTING)
}
