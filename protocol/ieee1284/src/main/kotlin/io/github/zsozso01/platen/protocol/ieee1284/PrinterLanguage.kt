package io.github.zsozso01.platen.protocol.ieee1284

/**
 * Page-description and job-control languages a printer can announce in the `CMD:` field of its
 * IEEE 1284 Device ID. Only languages Platen can reason about are listed; anything else is kept as
 * a raw string in [Ieee1284DeviceId.commandSets].
 */
public enum class PrinterLanguage {
    /** Printer Job Language: job control and status, wraps the page language. */
    PJL,

    /** HP PCL 3 (legacy inkjet command language). */
    PCL3,

    /** HP PCL 3 GUI, the raster dialect used by host-based HP inkjets. */
    PCL3GUI,

    /** PCL 5 / 5e / 5c. */
    PCL5,

    /** PCL XL, also called PCL 6. */
    PCLXL,

    POSTSCRIPT,

    PDF,

    /** Apple Raster (`image/urf`). */
    URF,

    /** PWG Raster (`image/pwg-raster`). */
    PWG_RASTER,

    /** HP PCLm (`application/PCLm`), a PDF-wrapped raster format. */
    PCLM,

    /** Epson ESC/P-R and ESC/P2 families. */
    ESCP,

    ;

    public companion object {
        /** Maps one token from a `CMD:` field to a language, or null when it is not one we model. */
        public fun fromToken(token: String): PrinterLanguage? {
            val t = token.trim().uppercase().replace(" ", "").replace("-", "").replace("_", "")
            return when {
                t == "PJL" -> PJL
                t == "PCL3" -> PCL3
                t == "PCL3GUI" -> PCL3GUI
                t == "PCLXL" || t == "PCL6" -> PCLXL
                t == "PCL" || t == "PCL5" || t == "PCL5E" || t == "PCL5C" || t.startsWith("PCL5") -> PCL5
                t == "PCLM" -> PCLM
                t.startsWith("POSTSCRIPT") || t == "PS" || t == "PS2" || t == "PS3" || t.startsWith("PSLEVEL") -> POSTSCRIPT
                t.startsWith("PDF") -> PDF
                t == "URF" -> URF
                t == "PWGRASTER" || t == "PWG" -> PWG_RASTER
                t.startsWith("ESCP") || t == "ESCPR" -> ESCP
                else -> null
            }
        }
    }
}
