package io.github.zsozso01.platen.protocol.ipp

/**
 * Typed view of a `Get-Printer-Attributes` response. Every accessor reads the underlying attribute
 * lazily and returns an empty/null value when the printer did not report it: "not reported" is a
 * normal answer and the layers above must handle it.
 */
public class IppPrinterAttributes(public val group: IppGroup) {
    public operator fun get(name: String): IppAttribute? = group[name]?.takeUnless { it.isOutOfBand }

    public fun has(name: String): Boolean = get(name) != null

    private fun strings(name: String): List<String> = get(name)?.strings().orEmpty()

    private fun string(name: String): String? = get(name)?.string()

    // --- identity ----------------------------------------------------------------------------

    public val name: String? get() = string("printer-name")
    public val makeAndModel: String? get() = string("printer-make-and-model")
    public val info: String? get() = string("printer-info")
    public val location: String? get() = string("printer-location")
    public val uuid: String? get() = string("printer-uuid")

    /** The printer's IEEE 1284 Device ID string, if it reports one. */
    public val deviceId: String? get() = string("printer-device-id")

    public val ippVersions: List<IppVersion> get() = strings("ipp-versions-supported").mapNotNull(IppVersion::parse)
    public val operationsSupported: List<Int> get() = get("operations-supported")?.ints().orEmpty()

    public fun supportsOperation(operation: Int): Boolean = operation in operationsSupported

    public val uris: List<String> get() = strings("printer-uri-supported")
    public val uriSecurity: List<String> get() = strings("uri-security-supported")
    public val uriAuthentication: List<String> get() = strings("uri-authentication-supported")

    // --- state -------------------------------------------------------------------------------

    public val isAcceptingJobs: Boolean? get() = get("printer-is-accepting-jobs")?.boolean()
    public val state: Int? get() = get("printer-state")?.int()
    public val stateReasons: List<String> get() = strings("printer-state-reasons")
    public val stateMessage: String? get() = string("printer-state-message")

    // --- formats -----------------------------------------------------------------------------

    public val documentFormats: List<String> get() = strings("document-format-supported")
    public val documentFormatDefault: String? get() = string("document-format-default")
    public val documentFormatPreferred: String? get() = string("document-format-preferred")

    public fun supportsFormat(mime: String): Boolean = documentFormats.any { it.equals(mime, ignoreCase = true) }

    // --- job template ------------------------------------------------------------------------

    public val copiesSupported: IppRange? get() = get("copies-supported")?.ranges()?.firstOrNull()
    public val sidesSupported: List<String> get() = strings("sides-supported")
    public val sidesDefault: String? get() = string("sides-default")
    public val colorModesSupported: List<String> get() = strings("print-color-mode-supported")
    public val colorModeDefault: String? get() = string("print-color-mode-default")

    /** `print-quality` enum values: 3 draft, 4 normal, 5 high. */
    public val qualitiesSupported: List<Int> get() = get("print-quality-supported")?.ints().orEmpty()
    public val resolutionsSupported: List<IppResolution> get() = get("printer-resolution-supported")?.resolutions().orEmpty()
    public val resolutionDefault: IppResolution? get() = get("printer-resolution-default")?.resolutions()?.firstOrNull()
    public val orientationsSupported: List<Int> get() = get("orientation-requested-supported")?.ints().orEmpty()
    public val printScalingSupported: List<String> get() = strings("print-scaling-supported")
    public val pageRangesSupported: Boolean? get() = get("page-ranges-supported")?.boolean()
    public val multipleDocumentHandlingSupported: List<String> get() = strings("multiple-document-handling-supported")
    public val finishingsSupported: List<Int> get() = get("finishings-supported")?.ints().orEmpty()
    public val outputBinsSupported: List<String> get() = strings("output-bin-supported")
    public val jobCreationAttributesSupported: List<String> get() = strings("job-creation-attributes-supported")

    /** `number-up-supported` values (single ints and/or ranges expanded). */
    public val numberUpSupported: List<Int>
        get() = get("number-up-supported")?.values.orEmpty().flatMap {
            when (it) {
                is IppInteger -> listOf(it.value)
                is IppRange -> if (it.upper - it.lower in 0..64) (it.lower..it.upper).toList() else listOf(it.lower, it.upper)
                else -> emptyList()
            }
        }

    // --- media -------------------------------------------------------------------------------

    public val mediaSupported: List<String> get() = strings("media-supported")
    public val mediaDefault: String? get() = string("media-default")
    public val mediaReady: List<String> get() = strings("media-ready")
    public val mediaSourcesSupported: List<String> get() = strings("media-source-supported")
    public val mediaTypesSupported: List<String> get() = strings("media-type-supported")

    /** Unprintable-border values the printer lists, in hundredths of a millimetre. The largest is the safe default border. */
    public val mediaTopMarginsSupported: List<Int> get() = get("media-top-margin-supported")?.ints().orEmpty()
    public val mediaBottomMarginsSupported: List<Int> get() = get("media-bottom-margin-supported")?.ints().orEmpty()
    public val mediaLeftMarginsSupported: List<Int> get() = get("media-left-margin-supported")?.ints().orEmpty()
    public val mediaRightMarginsSupported: List<Int> get() = get("media-right-margin-supported")?.ints().orEmpty()

    /** One collection per paper/tray combination the printer can use, when it reports `media-col-database`. */
    public val mediaColDatabase: List<IppCollection> get() = get("media-col-database")?.collections().orEmpty()

    /** `media-supported` keywords that are real sizes (not `custom_min_...` or vendor names). */
    public val mediaSizes: List<PwgMediaSize> get() = mediaSupported.mapNotNull(PwgMediaSize::parse)

    /** The custom paper size range, when the printer advertises `custom_min_`/`custom_max_` keywords. */
    public val customMediaMinimum: PwgMediaSize?
        get() = mediaSupported.firstNotNullOfOrNull { PwgMediaSize.parseCustomLimit(it)?.takeIf { l -> l.isMinimum }?.size }
    public val customMediaMaximum: PwgMediaSize?
        get() = mediaSupported.firstNotNullOfOrNull { PwgMediaSize.parseCustomLimit(it)?.takeIf { l -> !l.isMinimum }?.size }

    // --- raster capabilities (what to generate when the printer has no PDF) -----------------

    /** Apple Raster `urf-supported` keywords, e.g. `CP1`, `W8`, `SRGB24`, `RS300-600`, `DM1`. */
    public val urfSupported: List<String> get() = strings("urf-supported")
    public val pwgRasterResolutions: List<IppResolution> get() = get("pwg-raster-document-resolution-supported")?.resolutions().orEmpty()
    public val pwgRasterTypes: List<String> get() = strings("pwg-raster-document-type-supported")
    public val pwgRasterSheetBack: String? get() = string("pwg-raster-document-sheet-back")
    public val pclmSourceResolutions: List<IppResolution> get() = get("pclm-source-resolution-supported")?.resolutions().orEmpty()
    public val pclmCompressionPreferred: List<String> get() = strings("pclm-compression-method-preferred")
    public val pclmStripHeightPreferred: Int? get() = get("pclm-strip-height-preferred")?.int()
    public val pclmRasterBackSide: String? get() = string("pclm-raster-back-side")

    // --- supplies ----------------------------------------------------------------------------

    public val markerNames: List<String> get() = strings("marker-names")
    public val markerTypes: List<String> get() = strings("marker-types")
    public val markerColors: List<String> get() = strings("marker-colors")
    public val markerLevels: List<Int> get() = get("marker-levels")?.ints().orEmpty()
    public val markerLowLevels: List<Int> get() = get("marker-low-levels")?.ints().orEmpty()
}
