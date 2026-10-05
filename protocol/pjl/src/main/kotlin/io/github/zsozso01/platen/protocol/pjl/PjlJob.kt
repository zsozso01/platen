package io.github.zsozso01.platen.protocol.pjl

/**
 * Settings sent as `@PJL SET` commands before the page data. `null` means "do not send, keep the
 * printer's own default". Values are the PJL spellings; mapping from user-level settings to these
 * spellings (and deciding which the printer actually accepts) is the job of the PJL route, which
 * checks them against what the printer reported in `@PJL INFO VARIABLES`.
 */
public data class PjlJobSettings(
    val jobName: String? = null,
    /** `SET COPIES`: number of copies. */
    val copies: Int? = null,
    /** `SET QTY`: quantity of collated copies for languages that do not handle copies themselves. */
    val quantity: Int? = null,
    /** `SET DUPLEX=ON|OFF`. */
    val duplex: Boolean? = null,
    /** `SET BINDING=LONGEDGE|SHORTEDGE`. Only meaningful with [duplex] on. */
    val binding: Binding? = null,
    /** `SET PAPER=`, e.g. `A4`, `LETTER`. */
    val paper: String? = null,
    /** `SET MEDIASOURCE=`, e.g. `TRAY1`, `MANUAL`. */
    val mediaSource: String? = null,
    /** `SET MEDIATYPE=`. */
    val mediaType: String? = null,
    /** `SET OUTBIN=`. */
    val outBin: String? = null,
    /** `SET RESOLUTION=`, in dpi (e.g. 300, 600, 1200). */
    val resolutionDpi: Int? = null,
    /** `SET RENDERMODE=COLOR|GRAYSCALE`. */
    val renderMode: RenderMode? = null,
    /** `SET ECONOMODE=ON|OFF` (toner save). */
    val economode: Boolean? = null,
    /** `SET ORIENTATION=PORTRAIT|LANDSCAPE`. */
    val orientation: Orientation? = null,
    /** Anything else, sent verbatim as `@PJL SET <key>=<value>`. Keys are validated to `[A-Z0-9_]+`. */
    val extra: Map<String, String> = emptyMap(),
    /**
     * Ask the printer to report job and device status (`USTATUS`) on its read channel: when the job starts
     * and ends, and when the printer needs attention. Printers that do not support it ignore the request.
     */
    val statusReporting: Boolean = false,
) {
    public enum class Binding(internal val pjl: String) { LONG_EDGE("LONGEDGE"), SHORT_EDGE("SHORTEDGE") }

    public enum class RenderMode(internal val pjl: String) { COLOR("COLOR"), GRAYSCALE("GRAYSCALE") }

    public enum class Orientation(internal val pjl: String) { PORTRAIT("PORTRAIT"), LANDSCAPE("LANDSCAPE") }
}

/**
 * Builds the bytes that go before and after the page data of a PJL-wrapped job:
 *
 * ```
 * <UEL>@PJL JOB NAME="..."
 * @PJL SET COPIES=2
 * ...
 * @PJL ENTER LANGUAGE=PDF
 * <page data>
 * <UEL>@PJL EOJ NAME="..."
 * <UEL>
 * ```
 */
public class PjlJobBuilder(private val settings: PjlJobSettings = PjlJobSettings()) {
    private val sanitizedName: String? = settings.jobName?.let(Pjl::sanitizeString)?.takeIf(String::isNotEmpty)

    /** Everything that precedes the page data, ending with `@PJL ENTER LANGUAGE=...`. */
    public fun header(language: Pjl.Language): ByteArray = buildString {
        append(Pjl.UEL)
        if (settings.statusReporting) {
            // Must come before the JOB command to cover the whole job.
            append("@PJL USTATUS JOB=ON").append(Pjl.EOL)
            append("@PJL USTATUS DEVICE=ON").append(Pjl.EOL)
        }
        if (sanitizedName != null) {
            append("@PJL JOB NAME=\"").append(sanitizedName).append('"').append(Pjl.EOL)
        } else if (!settings.statusReporting) {
            // A bare "@PJL" opens the PJL context for printers that want one before the first command.
            append("@PJL").append(Pjl.EOL)
        }
        settings.copies?.let { set("COPIES", it.coerceIn(1, MAX_COPIES).toString()) }
        settings.quantity?.let { set("QTY", it.coerceIn(1, MAX_COPIES).toString()) }
        settings.duplex?.let { set("DUPLEX", if (it) "ON" else "OFF") }
        if (settings.duplex == true) settings.binding?.let { set("BINDING", it.pjl) }
        settings.paper?.let { set("PAPER", it.requireToken("PAPER")) }
        settings.mediaSource?.let { set("MEDIASOURCE", it.requireToken("MEDIASOURCE")) }
        settings.mediaType?.let { set("MEDIATYPE", it.requireToken("MEDIATYPE")) }
        settings.outBin?.let { set("OUTBIN", it.requireToken("OUTBIN")) }
        settings.resolutionDpi?.let { set("RESOLUTION", it.toString()) }
        settings.renderMode?.let { set("RENDERMODE", it.pjl) }
        settings.economode?.let { set("ECONOMODE", if (it) "ON" else "OFF") }
        settings.orientation?.let { set("ORIENTATION", it.pjl) }
        for ((key, value) in settings.extra) set(key.requireVariable(), value.requireToken(key))
        append("@PJL ENTER LANGUAGE=").append(language.pjlName).append(Pjl.EOL)
    }.toByteArray(Charsets.US_ASCII)

    /** Ends the language and the job. Send after the last byte of page data. */
    public fun footer(): ByteArray = buildString {
        append(Pjl.UEL)
        append("@PJL EOJ")
        sanitizedName?.let { append(" NAME=\"").append(it).append('"') }
        append(Pjl.EOL)
        append(Pjl.UEL)
    }.toByteArray(Charsets.US_ASCII)

    private fun StringBuilder.set(variable: String, value: String) {
        append("@PJL SET ").append(variable).append('=').append(value).append(Pjl.EOL)
    }

    private fun String.requireToken(what: String): String {
        require(isNotEmpty() && all { it.code in 0x21..0x7E && it != '"' }) { "Illegal PJL value for $what: '$this'" }
        return this
    }

    private fun String.requireVariable(): String {
        require(isNotEmpty() && all { it in 'A'..'Z' || it in '0'..'9' || it == '_' }) { "Illegal PJL variable name: '$this'" }
        return this
    }

    private companion object {
        const val MAX_COPIES = 999
    }
}
