package io.github.zsozso01.platen.protocol.ieee1284

/**
 * A parsed IEEE 1284 Device ID, the identification string every USB printer returns for the
 * class-specific `GET_DEVICE_ID` request (and many network printers return over SNMP or in their
 * mDNS records).
 *
 * Example (synthetic):
 * `MFG:Acme;MDL:LaserWriter 9000;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF;CLS:PRINTER;DES:Acme LaserWriter 9000;`
 *
 * Parsing never throws: a printer with a malformed ID must still be listed, just with fewer fields.
 */
public class Ieee1284DeviceId private constructor(
    /** All fields with upper-cased keys exactly as sent, in order. Repeated keys are joined with `,`. */
    public val fields: Map<String, String>,
    /** The ID as text, without the 2-byte length prefix. */
    public val raw: String,
) {
    public val manufacturer: String? get() = field("MFG", "MANUFACTURER")
    public val model: String? get() = field("MDL", "MODEL")
    public val description: String? get() = field("DES", "DESCRIPTION")
    public val deviceClass: String? get() = field("CLS", "CLASS")
    public val serialNumber: String? get() = field("SN", "SERN", "SERIALNUMBER")
    public val compatibleIds: List<String> get() = field("CID", "COMPATIBLEID")?.splitList().orEmpty()

    /** Command sets exactly as announced, e.g. `["PJL", "PCL", "POSTSCRIPT"]`. */
    public val commandSets: List<String> get() = field("CMD", "COMMANDSET")?.splitList().orEmpty()

    /** [commandSets] mapped onto the languages Platen models. Unknown tokens are skipped. */
    public val languages: Set<PrinterLanguage>
        get() = commandSets.mapNotNullTo(linkedSetOf(), PrinterLanguage::fromToken)

    /** A human-friendly name: the description, else "manufacturer model", else the model. */
    public val displayName: String?
        get() = description?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(manufacturer, model).joinToString(" ").takeIf { it.isNotBlank() }

    private fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { fields[it]?.takeIf(String::isNotBlank) }

    private fun String.splitList(): List<String> = split(',').map(String::trim).filter(String::isNotEmpty)

    override fun toString(): String = "Ieee1284DeviceId($raw)"

    override fun equals(other: Any?): Boolean = other is Ieee1284DeviceId && other.fields == fields

    override fun hashCode(): Int = fields.hashCode()

    public companion object {
        /** Parses the textual form (`KEY:value;KEY:value;`). */
        public fun parse(text: String): Ieee1284DeviceId {
            val cleaned = text.replace("\u0000", "").trim()
            val fields = linkedMapOf<String, String>()
            for (part in cleaned.split(';')) {
                val colon = part.indexOf(':')
                if (colon <= 0) continue
                val key = part.substring(0, colon).trim().uppercase().replace(" ", "")
                val value = part.substring(colon + 1).trim()
                if (key.isEmpty()) continue
                fields[key] = fields[key]?.let { "$it,$value" } ?: value
            }
            return Ieee1284DeviceId(fields, cleaned)
        }

        /**
         * Parses the byte form returned by the USB `GET_DEVICE_ID` control transfer.
         *
         * The spec says the first two bytes are a big-endian length that includes themselves. Real
         * printers deviate: some send little-endian, some send a length that is too small or too
         * large, some omit it. This accepts all of those by checking that the text after the prefix
         * really looks like an ID.
         */
        public fun parse(bytes: ByteArray): Ieee1284DeviceId {
            if (bytes.isEmpty()) return parse("")
            val body = stripLengthPrefix(bytes)
            return parse(String(body, Charsets.ISO_8859_1))
        }

        /**
         * A real ID is far shorter than 8 KiB, so a genuine length prefix always has at least one
         * byte below 0x20 (big- or little-endian). Two printable bytes can only be the start of the
         * text itself, e.g. "MF" for "MFG:". This holds for every endianness and length quirk.
         */
        private fun stripLengthPrefix(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && !looksLikeText(bytes)) bytes.copyOfRange(2, bytes.size) else bytes

        private fun looksLikeText(bytes: ByteArray): Boolean =
            (bytes[0].toInt() and 0xFF) in 0x20..0x7E && (bytes[1].toInt() and 0xFF) in 0x20..0x7E
    }
}
