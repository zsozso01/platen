package io.github.zsozso01.platen.transport.network

/** A resolved DNS-SD service as the platform hands it over: the raw material for [DnsSdPrinters]. */
public class DnsSdService(
    /** The instance name, e.g. `HP DeskJet 3700 series [766F51]`. */
    public val instanceName: String,
    public val host: String,
    public val port: Int,
    /** TXT record, keys as sent (matching is case-insensitive). */
    public val txt: Map<String, String>,
    /** True for `_ipps._tcp`. */
    public val secure: Boolean,
)

/** A printer found on the network. */
public data class DiscoveredPrinter(
    /** Stable key: the printer's UUID when it advertises one, else its address. */
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** HTTP path, always starting with `/`. */
    val path: String,
    /** The `ty` TXT value (make and model). */
    val makeAndModel: String?,
    val uuid: String?,
    /** `Color` TXT: true, false, or null when not stated. */
    val color: Boolean?,
    val duplex: Boolean?,
    /** The `pdl` TXT value: MIME types the printer accepts. A hint only; IPP is authoritative. */
    val formats: List<String>,
    /** The service is `_ipps._tcp`: it needs an encrypted connection. */
    val secure: Boolean,
) {
    /** What to type into [PrinterAddress.parse]. */
    public val address: String get() = "${if (secure) "ipps" else "ipp"}://${if (host.contains(':')) "[$host]" else host}:$port$path"
}

/** Interprets `_ipp._tcp` / `_ipps._tcp` services (Bonjour Printing spec, PWG 5100.14). */
public object DnsSdPrinters {
    /** Returns null for services that are not usable printers (no port, nothing to talk to). */
    public fun fromService(service: DnsSdService): DiscoveredPrinter? {
        if (service.port <= 0 || service.host.isBlank()) return null
        val txt = service.txt.mapKeys { it.key.lowercase() }
        // `rp` is the resource path without its leading slash; some printers send one anyway.
        val rp = txt["rp"]?.trim()?.trim('/').orEmpty()
        val path = if (rp.isEmpty()) "/ipp/print" else "/$rp"
        val ty = txt["ty"]?.trim()?.takeIf { it.isNotEmpty() }
        val uuid = txt["uuid"]?.trim()?.removePrefix("urn:uuid:")?.lowercase()?.takeIf { it.isNotEmpty() }
        return DiscoveredPrinter(
            id = uuid ?: "${service.host}:${service.port}$path",
            name = service.instanceName.trim().ifEmpty { ty ?: service.host },
            host = service.host,
            port = service.port,
            path = path,
            makeAndModel = ty,
            uuid = uuid,
            color = flag(txt["color"]),
            duplex = flag(txt["duplex"]),
            formats = txt["pdl"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            secure = service.secure,
        )
    }

    private fun flag(value: String?): Boolean? = when (value?.trim()?.uppercase()) {
        "T" -> true
        "F" -> false
        else -> null // "U" or absent: unknown
    }

    /**
     * One entry per physical printer: services with the same UUID (or the same address) collapse, and the
     * plain-IPP entry wins over the encrypted one because that is what Platen can use today. Sorted by name.
     */
    public fun merge(printers: List<DiscoveredPrinter>): List<DiscoveredPrinter> =
        printers.groupBy { it.id }
            .map { (_, group) -> group.sortedWith(compareBy<DiscoveredPrinter> { it.secure }.thenBy { it.port }).first() }
            .sortedBy { it.name.lowercase() }
}
