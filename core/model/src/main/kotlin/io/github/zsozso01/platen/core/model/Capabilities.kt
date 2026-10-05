package io.github.zsozso01.platen.core.model

/** A document or page-description format, identified by its MIME type. */
@JvmInline
public value class DocumentFormat(public val mime: String) {
    override fun toString(): String = mime

    public companion object {
        public val PDF: DocumentFormat = DocumentFormat("application/pdf")
        public val POSTSCRIPT: DocumentFormat = DocumentFormat("application/postscript")
        public val PCL: DocumentFormat = DocumentFormat("application/vnd.hp-pcl")
        public val PWG_RASTER: DocumentFormat = DocumentFormat("image/pwg-raster")
        public val URF: DocumentFormat = DocumentFormat("image/urf")
        public val PCLM: DocumentFormat = DocumentFormat("application/PCLm")
        public val JPEG: DocumentFormat = DocumentFormat("image/jpeg")
    }
}

/** Where a piece of capability information came from. Lets the UI tell "the printer says" from "we assume". */
public enum class Provenance {
    /** The printer reported it over IPP. The most trustworthy source. */
    REPORTED_IPP,

    /** The printer reported it as PJL variables or configuration. */
    REPORTED_PJL,

    /** Taken from the IEEE 1284 Device ID. The printer *claims* it; spellings are unreliable. */
    DEVICE_ID_HINT,

    /** A sensible default used because the printer said nothing. */
    ASSUMED,

    /** The user set it by hand, overriding detection. */
    USER_OVERRIDE,
}

/** The facets of [PrinterCapabilities] that have a [Provenance]. */
public enum class CapabilityKey {
    FORMATS, SIDES, COLOR, QUALITY, RESOLUTION, MEDIA, TRAYS, COPIES, PAGE_RANGES, ORIENTATION, MARGINS, RASTER, SCALING, NUMBER_UP, FINISHING,
}

/** How the *printer* can scale a document it prints itself (PDF pass-through). Mirrors IPP `print-scaling`. */
public enum class PrinterScaling {
    /** The printer chooses between fit and fill. */
    AUTO,

    /** Shrink to fit if too big, otherwise leave alone. */
    SHRINK_TO_FIT,

    FIT,

    FILL,

    /** No scaling. */
    NONE,
}

/** Parameters the printer accepts for raster formats (PWG Raster / Apple Raster), needed when it has no PDF. */
public data class RasterProfile(
    /** Colour types as IPP keywords, e.g. `srgb_8`, `sgray_8`. */
    val colorTypes: List<String>,
    val resolutionsDpi: List<Int>,
    /** How the back side of a duplex page is oriented: `normal`, `flipped`, `rotated`, `manual-tumble`. */
    val sheetBack: String? = null,
)

/** A consumable such as an ink cartridge or toner. */
public data class Supply(
    val name: String,
    /** 0 to 100, or null if the printer does not know. */
    val levelPercent: Int?,
    val colorHex: String? = null,
    val isLow: Boolean = false,
)

/**
 * What a printer can do, as far as we could find out. Empty or null means "not reported", which the
 * planner treats conservatively. Never throws and never needs a printer database.
 */
public data class PrinterCapabilities(
    val formats: List<DocumentFormat> = emptyList(),
    val sides: Set<Sides> = setOf(Sides.ONE_SIDED),
    val colorModes: Set<ColorMode> = setOf(ColorMode.AUTO),
    val qualities: Set<Quality> = emptySet(),
    val resolutionsDpi: List<Int> = emptyList(),
    val mediaSizes: List<MediaSize> = emptyList(),
    val defaultMedia: MediaSize? = null,
    val trays: List<MediaSource> = emptyList(),
    val mediaTypes: List<MediaType> = emptyList(),
    val outputBins: List<String> = emptyList(),
    /** Largest `copies` value accepted, or null if unknown. */
    val maxCopies: Int? = null,
    val supportsPageRanges: Boolean = false,
    /** Pages per sheet the printer can impose itself. */
    val numberUp: Set<Int> = setOf(1),
    val printerScaling: Set<PrinterScaling> = emptySet(),
    /** The printer can fold and staple booklets itself (IPP finishing `booklet-maker`). */
    val bookletMaker: Boolean = false,
    val orientations: Set<Orientation> = setOf(Orientation.PORTRAIT),
    /** The unprintable border, when known. */
    val unprintableMargins: Margins? = null,
    val raster: RasterProfile? = null,
    val supplies: List<Supply> = emptyList(),
    val provenance: Map<CapabilityKey, Provenance> = emptyMap(),
) {
    public fun supports(format: DocumentFormat): Boolean = formats.any { it.mime.equals(format.mime, ignoreCase = true) }

    public val canDuplex: Boolean get() = sides.any { it.isDuplex }

    public fun provenanceOf(key: CapabilityKey): Provenance = provenance[key] ?: Provenance.ASSUMED
}
