package io.github.zsozso01.platen.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.zsozso01.platen.R
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.PrinterIssue
import java.util.Locale

/** A human name for a paper size: "A4", "Letter", ... with a metric or inch size as fallback. */
fun paperLabel(size: MediaSize): String {
    val known = size.pwgName?.let { keyword ->
        val parts = keyword.split('_')
        val cls = parts.getOrNull(0)
        val name = parts.getOrNull(1)
        when {
            cls == "iso" && name != null && name.matches(Regex("[abc]\\d+")) -> name.uppercase(Locale.ROOT)
            cls == "jis" && name?.startsWith("b") == true -> "JIS ${name.uppercase(Locale.ROOT)}"
            cls == "na" && name == "letter" -> "Letter"
            cls == "na" && name == "legal" -> "Legal"
            cls == "na" && name == "executive" -> "Executive"
            cls == "na" && name == "invoice" -> "Statement"
            cls == "na" && name?.startsWith("index") == true -> name.removePrefix("index-").replace('x', '×') + " in index card"
            cls == "oe" && name?.startsWith("photo") == true -> "Photo"
            else -> null
        }
    }
    val millimetres = "%.0f × %.0f mm".format(Locale.ROOT, size.widthMm, size.heightMm)
    return if (known != null) "$known ($millimetres)" else millimetres
}

@Composable
fun issueText(issue: PrinterIssue): String = stringResource(
    when (issue.kind) {
        PrinterIssue.Kind.PAPER_OUT -> R.string.issue_paper_out
        PrinterIssue.Kind.PAPER_JAM -> R.string.issue_paper_jam
        PrinterIssue.Kind.COVER_OPEN -> R.string.issue_cover_open
        PrinterIssue.Kind.DOOR_OPEN -> R.string.issue_door_open
        PrinterIssue.Kind.TONER_LOW -> R.string.issue_toner_low
        PrinterIssue.Kind.TONER_EMPTY -> R.string.issue_toner_empty
        PrinterIssue.Kind.INK_LOW -> R.string.issue_ink_low
        PrinterIssue.Kind.INK_EMPTY -> R.string.issue_ink_empty
        PrinterIssue.Kind.OUTPUT_FULL -> R.string.issue_output_full
        PrinterIssue.Kind.OFFLINE -> R.string.issue_offline
        PrinterIssue.Kind.PAUSED -> R.string.issue_paused
        PrinterIssue.Kind.BUSY -> R.string.issue_busy
        PrinterIssue.Kind.TRAY_MISSING -> R.string.issue_tray_missing
        PrinterIssue.Kind.OTHER -> R.string.issue_other
    },
)

@Composable
fun failureText(failure: PrintFailure): String = when (failure) {
    is PrintFailure.Unreachable -> stringResource(R.string.fail_unreachable)
    is PrintFailure.AuthenticationRequired -> stringResource(R.string.fail_auth)
    is PrintFailure.EncryptionRequired -> stringResource(R.string.fail_encryption)
    is PrintFailure.UntrustedCertificate -> stringResource(R.string.fail_certificate)
    is PrintFailure.Rejected -> stringResource(R.string.fail_rejected, failure.reason)
    is PrintFailure.PreparationFailed -> stringResource(R.string.fail_preparation, failure.reason)
    is PrintFailure.UsbProblem -> stringResource(R.string.fail_usb, failure.reason)
    is PrintFailure.NeedsAttention -> failure.issues.map { issueText(it) }.joinToString("\n")
    is PrintFailure.Unexpected -> stringResource(R.string.fail_unexpected)
}
