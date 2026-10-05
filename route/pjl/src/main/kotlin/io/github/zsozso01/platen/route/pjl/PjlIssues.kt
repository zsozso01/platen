package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.core.model.Severity
import io.github.zsozso01.platen.protocol.pjl.PjlStatus
import io.github.zsozso01.platen.protocol.pjl.PjlStatusClass

/**
 * Turns a PJL status report into Platen's vocabulary. The numeric range says how serious it is; the
 * printer's own display text (which varies by model and language) says what it is, so that is matched by
 * keyword. The code and the original text are always kept for diagnostics.
 */
internal object PjlIssues {
    fun state(status: PjlStatus?): PrinterState {
        if (status == null) return PrinterState.UNKNOWN
        val text = status.display.orEmpty().uppercase()
        return when {
            status.statusClass == PjlStatusClass.OPERATOR_INTERVENTION || status.online == false -> PrinterState.STOPPED
            "PRINTING" in text || "PROCESSING" in text || "RECEIVING" in text -> PrinterState.PRINTING
            status.statusClass == PjlStatusClass.INFORMATIONAL -> PrinterState.IDLE
            else -> PrinterState.UNKNOWN
        }
    }

    /**
     * Problems a person should know about. A PJL error (the printer disliked part of our job header) is not
     * one of them: it is a diagnostics matter, not something the user can fix.
     */
    fun from(status: PjlStatus): List<PrinterIssue> {
        val severity = when {
            status.statusClass == PjlStatusClass.OPERATOR_INTERVENTION || status.online == false -> Severity.ERROR
            status.statusClass == PjlStatusClass.ATTENTION -> Severity.WARNING
            else -> return emptyList()
        }
        val text = status.display.orEmpty().uppercase()
        val supplyWord = "TONER" in text || "CARTRIDGE" in text
        val kind = when {
            "JAM" in text -> PrinterIssue.Kind.PAPER_JAM
            supplyWord && ("LOW" in text || "ORDER" in text) -> PrinterIssue.Kind.TONER_LOW
            supplyWord -> PrinterIssue.Kind.TONER_EMPTY
            "DOOR" in text -> PrinterIssue.Kind.DOOR_OPEN
            "COVER" in text -> PrinterIssue.Kind.COVER_OPEN
            "TRAY" in text && ("REMOVED" in text || "MISSING" in text || "OPEN" in text) -> PrinterIssue.Kind.TRAY_MISSING
            "PAPER OUT" in text || "OUT OF PAPER" in text || "LOAD" in text || "ADD PAPER" in text || "EMPTY" in text -> PrinterIssue.Kind.PAPER_OUT
            "OFFLINE" in text || status.online == false -> PrinterIssue.Kind.OFFLINE
            else -> PrinterIssue.Kind.OTHER
        }
        return listOf(PrinterIssue(kind, severity, raw = status.code?.toString(), message = status.display))
    }
}
