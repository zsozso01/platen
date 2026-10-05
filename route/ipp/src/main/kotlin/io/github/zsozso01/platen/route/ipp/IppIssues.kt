package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Severity
import io.github.zsozso01.platen.protocol.ipp.IppPrinterState

/** Maps `printer-state` and `printer-state-reasons` (RFC 8011 5.4.11 and 5.4.12) onto Platen's own vocabulary. */
public object IppIssues {
    public fun state(value: Int?): PrinterState = when (value) {
        IppPrinterState.IDLE -> PrinterState.IDLE
        IppPrinterState.PROCESSING -> PrinterState.PRINTING
        IppPrinterState.STOPPED -> PrinterState.STOPPED
        else -> PrinterState.UNKNOWN
    }

    /**
     * Each reason keyword may end in `-error`, `-warning` or `-report`. With no suffix a reason is an
     * error if the printer is stopped and a warning otherwise. `none` means no issue.
     */
    public fun reasons(keywords: List<String>, stopped: Boolean): List<PrinterIssue> =
        keywords.filter { it != "none" }.mapNotNull { raw ->
            val (base, severity) = when {
                raw.endsWith("-error") -> raw.removeSuffix("-error") to Severity.ERROR
                raw.endsWith("-warning") -> raw.removeSuffix("-warning") to Severity.WARNING
                raw.endsWith("-report") -> raw.removeSuffix("-report") to Severity.INFO
                else -> raw to if (stopped) Severity.ERROR else Severity.WARNING
            }
            val kind = kindOf(base) ?: return@mapNotNull if (severity == Severity.INFO) null else PrinterIssue(PrinterIssue.Kind.OTHER, severity, raw)
            PrinterIssue(kind, severity, raw)
        }

    private fun kindOf(base: String): PrinterIssue.Kind? = when (base) {
        "media-empty", "media-needed" -> PrinterIssue.Kind.PAPER_OUT
        "media-jam" -> PrinterIssue.Kind.PAPER_JAM
        "cover-open" -> PrinterIssue.Kind.COVER_OPEN
        "door-open", "interlock-open" -> PrinterIssue.Kind.DOOR_OPEN
        "toner-low" -> PrinterIssue.Kind.TONER_LOW
        "toner-empty" -> PrinterIssue.Kind.TONER_EMPTY
        "marker-supply-low", "marker-ink-almost-empty" -> PrinterIssue.Kind.INK_LOW
        "marker-supply-empty", "marker-ink-empty" -> PrinterIssue.Kind.INK_EMPTY
        "output-area-full" -> PrinterIssue.Kind.OUTPUT_FULL
        "paused" -> PrinterIssue.Kind.PAUSED
        "offline", "shutdown" -> PrinterIssue.Kind.OFFLINE
        "input-tray-missing" -> PrinterIssue.Kind.TRAY_MISSING
        // Informational reasons that are not problems for the user.
        "media-low", "identify-printer-requested", "other", "connecting-to-device", "spool-area-full" -> null
        else -> null
    }
}
