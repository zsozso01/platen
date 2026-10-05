package io.github.zsozso01.platen.protocol.pjl

/** One response block from the printer: the echoed command and its body lines. */
public data class PjlResponse(
    /** The echoed command, e.g. `INFO ID`, `INFO STATUS`, `USTATUS DEVICE`, `ECHO PLATEN-DONE`. */
    val command: String,
    /** Body lines with surrounding whitespace removed. */
    val body: List<String>,
) {
    /** `KEY=VALUE` lines as a map (values unquoted). Later duplicates win. */
    public val keyValues: Map<String, String> by lazy {
        buildMap {
            for (line in body) {
                val eq = line.indexOf('=')
                if (eq > 0 && !line.startsWith("\"")) put(line.substring(0, eq).trim().uppercase(), unquote(line.substring(eq + 1)))
            }
        }
    }

    /** Body for responses that are a single quoted string, e.g. `INFO ID`. */
    public val text: String? get() = body.firstOrNull()?.let(::unquote)

    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")
}

/** Parses PJL responses, tolerating the many ways printers deviate from the spec. */
public object PjlResponseParser {
    /** Splits [bytes] at form feeds and parses every block. Never throws. */
    public fun parse(bytes: ByteArray): List<PjlResponse> = parse(String(bytes, Charsets.ISO_8859_1))

    public fun parse(text: String): List<PjlResponse> =
        text.split(Pjl.RESPONSE_TERMINATOR)
            .mapNotNull(::parseBlock)

    /** True once the echoed [PjlQueries.DONE_MARKER] has arrived, i.e. the whole response is in. */
    public fun isComplete(bytes: ByteArray): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains("@PJL ECHO ${PjlQueries.DONE_MARKER}")

    private fun parseBlock(block: String): PjlResponse? {
        val cleaned = block.replace(Pjl.UEL, "").replace("\u0000", "")
        val lines = cleaned.lines()
        val headerIndex = lines.indexOfFirst { it.trimStart().startsWith("@PJL", ignoreCase = true) }
        if (headerIndex < 0) return null
        val command = lines[headerIndex].trim().drop(4).trim()
        if (command.isEmpty()) return null
        val body = lines.drop(headerIndex + 1).map(String::trim).filter(String::isNotEmpty)
        return PjlResponse(command, body)
    }
}
