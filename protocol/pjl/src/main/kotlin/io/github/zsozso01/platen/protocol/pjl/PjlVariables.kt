package io.github.zsozso01.platen.protocol.pjl

/**
 * One entry of `@PJL INFO VARIABLES` or `@PJL INFO CONFIG`: a name, an optional current value and,
 * for settable variables, the values the printer says are legal.
 *
 * ```
 * DUPLEX=OFF [2 ENUMERATED]
 *     OFF
 *     ON
 * COPIES=1 [2 RANGE]
 *     1
 *     999
 * ```
 */
public data class PjlVariable(
    val name: String,
    val current: String?,
    val kind: Kind,
    /** For [Kind.ENUMERATED] the legal values; for [Kind.RANGE] the minimum and maximum. */
    val allowed: List<String>,
) {
    public enum class Kind { ENUMERATED, RANGE, OTHER }

    /** For a [Kind.RANGE] variable, its bounds. */
    public val range: IntRange?
        get() = if (kind == Kind.RANGE && allowed.size >= 2) {
            val lo = allowed[0].trim().toIntOrNull()
            val hi = allowed[1].trim().toIntOrNull()
            if (lo != null && hi != null) lo..hi else null
        } else {
            null
        }
}

/** Parses the body of `INFO VARIABLES` / `INFO CONFIG` into [PjlVariable]s. Never throws. */
public object PjlVariableParser {
    private val header = Regex("""^(.*?)\s*\[\s*(\d+)\s+([A-Za-z]+)\s*]\s*$""")

    public fun parse(response: PjlResponse): List<PjlVariable> = parse(response.body)

    public fun parse(body: List<String>): List<PjlVariable> {
        val result = mutableListOf<PjlVariable>()
        var i = 0
        while (i < body.size) {
            val m = header.matchEntire(body[i])
            if (m == null) {
                i++
                continue
            }
            val label = m.groupValues[1]
            val count = m.groupValues[2].toInt().coerceIn(0, MAX_ALLOWED_VALUES)
            val kind = when (m.groupValues[3].uppercase()) {
                "ENUMERATED" -> PjlVariable.Kind.ENUMERATED
                "RANGE" -> PjlVariable.Kind.RANGE
                else -> PjlVariable.Kind.OTHER
            }
            val eq = label.indexOf('=')
            val name = (if (eq >= 0) label.substring(0, eq) else label).trim().uppercase()
            val current = if (eq >= 0) label.substring(eq + 1).trim().removeSurrounding("\"") else null
            val allowed = body.subList(i + 1, minOf(body.size, i + 1 + count)).map { it.trim().removeSurrounding("\"") }
            result += PjlVariable(name, current, kind, allowed)
            i += 1 + allowed.size
        }
        return result
    }

    private const val MAX_ALLOWED_VALUES = 512
}
