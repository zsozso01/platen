package io.github.zsozso01.platen.protocol.pjl

/** Constants and low-level helpers for the Printer Job Language (PJL). */
public object Pjl {
    /** Universal Exit Language: `ESC % -12345 X`. Brackets every PJL job and ends the page language. */
    public const val UEL: String = "\u001B%-12345X"

    /** PJL lines end in CR LF. */
    public const val EOL: String = "\r\n"

    /** PJL responses are terminated by a form feed. */
    public const val RESPONSE_TERMINATOR: Char = '\u000C'

    /** Longest job name sent to a printer. Many devices truncate or reject longer names. */
    public const val MAX_JOB_NAME_LENGTH: Int = 80

    /**
     * Makes arbitrary text safe inside a PJL quoted string: no quotes, no control characters, only
     * printable ASCII (a device whose STRINGCODESET we do not know would show anything else as noise).
     */
    public fun sanitizeString(text: String, maxLength: Int = MAX_JOB_NAME_LENGTH): String =
        buildString {
            for (ch in text) {
                if (length >= maxLength) break
                when {
                    ch == '"' -> append('\'')
                    ch == ' ' || ch.isWhitespace() -> if (isNotEmpty() && last() != ' ') append(' ')
                    ch.code in 0x21..0x7E -> append(ch)
                    else -> append('?')
                }
            }
        }.trim()

    /** Names accepted by `@PJL ENTER LANGUAGE=`. */
    public enum class Language(public val pjlName: String) {
        PCL("PCL"),
        PCLXL("PCLXL"),
        POSTSCRIPT("POSTSCRIPT"),
        PDF("PDF"),
    }
}
