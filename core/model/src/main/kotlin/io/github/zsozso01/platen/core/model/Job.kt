package io.github.zsozso01.platen.core.model

/** Overall state of a printer, from the printer's own report. */
public enum class PrinterState { IDLE, PRINTING, STOPPED, UNKNOWN }

/** How serious a [PrinterIssue] is. */
public enum class Severity { INFO, WARNING, ERROR }

/** Why a printer or job needs attention. The UI maps [kind] to a message in the user's language. */
public data class PrinterIssue(
    val kind: Kind,
    val severity: Severity,
    /** The raw protocol code (an IPP keyword or PJL status code), kept for diagnostics. */
    val raw: String? = null,
    /** The printer's own wording, if it gave one. */
    val message: String? = null,
) {
    public enum class Kind {
        PAPER_OUT,
        PAPER_JAM,
        COVER_OPEN,
        TONER_LOW,
        TONER_EMPTY,
        INK_LOW,
        INK_EMPTY,
        OUTPUT_FULL,
        OFFLINE,
        PAUSED,
        BUSY,
        DOOR_OPEN,
        TRAY_MISSING,
        OTHER,
    }
}

/** Why a print job did not complete. Each case is something the UI can explain and often fix. */
public sealed interface PrintFailure {
    public val cause: Throwable?

    /** Could not reach the printer at all (off, asleep, wrong network, cable unplugged). */
    public data class Unreachable(override val cause: Throwable? = null) : PrintFailure

    /** The printer wants credentials. */
    public data class AuthenticationRequired(override val cause: Throwable? = null) : PrintFailure

    /** The printer requires an encrypted connection and the plain one was refused. */
    public data class EncryptionRequired(override val cause: Throwable? = null) : PrintFailure

    /** The printer's certificate is not one the user has approved. */
    public data class UntrustedCertificate(val fingerprintSha256: String, override val cause: Throwable? = null) : PrintFailure

    /** The printer refused the document format or a setting. */
    public data class Rejected(val reason: String, override val cause: Throwable? = null) : PrintFailure

    /** The printer is stopped for a reason a person can fix (paper, jam, cover). */
    public data class NeedsAttention(val issues: List<PrinterIssue>, override val cause: Throwable? = null) : PrintFailure

    /** The USB port is silent or disabled, or the device disappeared. */
    public data class UsbProblem(val reason: String, override val cause: Throwable? = null) : PrintFailure

    /** Our own rendering or encoding failed. */
    public data class PreparationFailed(val reason: String, override val cause: Throwable? = null) : PrintFailure

    public data class Unexpected(override val cause: Throwable? = null) : PrintFailure
}

/** Progress of one print job, as the engine reports it. */
public sealed interface JobEvent {
    /** Pages are being rendered or converted. [page] counts from 1. */
    public data class Preparing(val page: Int, val totalPages: Int) : JobEvent

    /** Bytes are being sent to the printer. [totalBytes] is null when unknown. */
    public data class Sending(val bytesSent: Long, val totalBytes: Long?) : JobEvent

    /** The printer accepted the job. [printerJobId] is whatever handle the protocol gave, if any. */
    public data class Accepted(val printerJobId: String?) : JobEvent

    /** The printer reports it is printing. [sheetsDone] is null if it does not say. */
    public data class Printing(val sheetsDone: Int?) : JobEvent

    /**
     * The printer needs a person to do something (add paper, close a cover) but the job is still alive
     * and will continue once they do. Not a final state.
     */
    public data class Attention(val issues: List<PrinterIssue>) : JobEvent

    /**
     * The job was sent, but the printer stopped answering status requests, so Platen cannot say how it
     * ended. It may well have printed. Final state of tracking, not necessarily of printing.
     */
    public data class Detached(val reason: String) : JobEvent

    /**
     * Manual duplex: the front sides are printed. The user must put the paper back so the *back* sides print
     * on the other face; the job waits until they say they have.
     */
    public data object NeedsReload : JobEvent

    public data object Completed : JobEvent

    public data object Canceled : JobEvent

    public data class Failed(val failure: PrintFailure) : JobEvent
}
