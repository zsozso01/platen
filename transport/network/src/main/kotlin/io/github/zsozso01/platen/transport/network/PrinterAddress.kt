package io.github.zsozso01.platen.transport.network

/**
 * What a person types to add a printer by hand: an IP address or host name, optionally with a port, or a
 * full `ipp://` URL. A bare host leaves [path] null so the caller can try the usual IPP paths.
 */
public data class PrinterAddress(
    val host: String,
    val port: Int,
    /** The HTTP path, or null when the user gave only a host. */
    val path: String?,
    /** True for `ipps://`. Encrypted connections are not supported yet. */
    val secure: Boolean,
) {
    public val hostHeader: String get() = if (port == DEFAULT_PORT) host else "$host:$port"

    public fun printerUri(path: String): String = "${if (secure) "ipps" else "ipp"}://$hostHeader$path"

    public companion object {
        public const val DEFAULT_PORT: Int = 631

        /** Paths printers commonly use, most likely first. */
        public val COMMON_PATHS: List<String> = listOf("/ipp/print", "/ipp/printer", "/ipp", "/")

        /** Parses user input. Returns null when it cannot be an address. */
        public fun parse(input: String): PrinterAddress? {
            var text = input.trim()
            if (text.isEmpty() || text.any { it.isWhitespace() }) return null
            var secure = false
            val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(text)
            if (scheme != null) {
                when (scheme.groupValues[1].lowercase()) {
                    "ipp", "http" -> Unit
                    "ipps", "https" -> secure = true
                    else -> return null
                }
                text = text.substring(scheme.value.length)
            }
            val slash = text.indexOf('/')
            val path = if (slash >= 0) text.substring(slash).takeIf { it.length > 1 } ?: "/" else null
            val authority = if (slash >= 0) text.substring(0, slash) else text
            if (authority.isEmpty()) return null

            val host: String
            var port = if (secure) 443 else DEFAULT_PORT
            if (authority.startsWith("[")) { // IPv6 literal
                val end = authority.indexOf(']')
                if (end < 0) return null
                host = authority.substring(1, end)
                val rest = authority.substring(end + 1)
                if (rest.isNotEmpty()) port = rest.removePrefix(":").toIntOrNull() ?: return null
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    port = authority.substring(colon + 1).toIntOrNull() ?: return null
                } else {
                    host = authority
                }
            }
            if (host.isEmpty() || port !in 1..65535) return null
            if (!host.all { it.isLetterOrDigit() || it in "-._:%" }) return null
            return PrinterAddress(host, port, path, secure)
        }
    }
}
