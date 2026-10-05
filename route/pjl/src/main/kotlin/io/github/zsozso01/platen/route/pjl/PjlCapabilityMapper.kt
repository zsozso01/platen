package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.model.CapabilityKey
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.Provenance
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.ieee1284.Ieee1284DeviceId
import io.github.zsozso01.platen.protocol.ieee1284.PrinterLanguage
import io.github.zsozso01.platen.protocol.pjl.PjlVariable

/**
 * Builds [PrinterCapabilities] from what a classic printer reveals: the IEEE 1284 Device ID (a hint),
 * and, if PJL answers, `INFO CONFIG` and `INFO VARIABLES`, which list the legal values of each setting.
 * Anything the printer did not say stays unreported.
 */
internal object PjlCapabilityMapper {
    /** Border assumed when the printer does not report one: about 4 mm, typical of laser printers. */
    private val ASSUMED_MARGINS = Margins.uniform(420)

    fun map(deviceId: Ieee1284DeviceId?, config: List<PjlVariable>, variables: List<PjlVariable>): PrinterCapabilities {
        val provenance = mutableMapOf<CapabilityKey, Provenance>()
        val vars = variables.associateBy { it.name }
        fun allowed(name: String): List<String> = vars[name]?.allowed.orEmpty()

        // Page languages: PJL's own list is better than the Device ID's, which is only a claim.
        val fromConfig = config.firstOrNull { it.name.startsWith("LANGUAGES") }?.allowed.orEmpty().mapNotNull { PrinterLanguage.fromToken(it) }
        val fromId = deviceId?.languages.orEmpty().toList()
        val languages = (fromConfig + fromId).toSet()
        val formats = buildList {
            if (PrinterLanguage.PDF in languages) add(DocumentFormat.PDF)
            if (PrinterLanguage.POSTSCRIPT in languages) add(DocumentFormat.POSTSCRIPT)
            if (PrinterLanguage.PCL5 in languages || PrinterLanguage.PCLXL in languages) add(DocumentFormat.PCL)
        }
        if (formats.isNotEmpty()) provenance[CapabilityKey.FORMATS] = if (fromConfig.isNotEmpty()) Provenance.REPORTED_PJL else Provenance.DEVICE_ID_HINT

        val duplexOn = "ON" in allowed("DUPLEX").map(String::uppercase)
        val bindings = allowed("BINDING").map { it.uppercase() }
        val sides = buildSet {
            add(Sides.ONE_SIDED)
            if (duplexOn) {
                if (bindings.isEmpty() || "LONGEDGE" in bindings) add(Sides.TWO_SIDED_LONG_EDGE)
                if (bindings.isEmpty() || "SHORTEDGE" in bindings) add(Sides.TWO_SIDED_SHORT_EDGE)
            }
        }
        if (vars.containsKey("DUPLEX")) provenance[CapabilityKey.SIDES] = Provenance.REPORTED_PJL

        // INFO VARIABLES lists what PAPER accepts; INFO CONFIG lists what is installed. Use the first that says anything.
        val configPapers = config.firstOrNull { it.name == "PAPERS" || it.name == "PAPER SIZES" }?.allowed.orEmpty()
        val sizes: List<MediaSize> = (allowed("PAPER").ifEmpty { configPapers }).mapNotNull { PjlPaper.toMedia(it) }.distinct()
        val currentPaper = vars["PAPER"]?.current?.let { PjlPaper.toMedia(it) }
        if (sizes.isNotEmpty()) provenance[CapabilityKey.MEDIA] = Provenance.REPORTED_PJL

        val trays = allowed("MEDIASOURCE").map { MediaSource(it) }
        if (trays.isNotEmpty()) provenance[CapabilityKey.TRAYS] = Provenance.REPORTED_PJL

        val copiesRange = (vars["COPIES"] ?: vars["QTY"])?.range
        if (copiesRange != null) provenance[CapabilityKey.COPIES] = Provenance.REPORTED_PJL

        val resolutions = allowed("RESOLUTION").mapNotNull { it.trim().toIntOrNull() }.sorted()
        if (resolutions.isNotEmpty()) provenance[CapabilityKey.RESOLUTION] = Provenance.REPORTED_PJL

        val renderModes = allowed("RENDERMODE").map { it.uppercase() }
        val colors = when {
            "COLOR" in renderModes -> setOf(ColorMode.AUTO, ColorMode.COLOR, ColorMode.MONOCHROME)
            renderModes.isNotEmpty() -> setOf(ColorMode.MONOCHROME)
            else -> setOf(ColorMode.AUTO)
        }
        if (renderModes.isNotEmpty()) provenance[CapabilityKey.COLOR] = Provenance.REPORTED_PJL

        // ECONOMODE is the only quality-like control PJL offers; draft maps onto it.
        val qualities = if (allowed("ECONOMODE").isNotEmpty()) setOf(Quality.NORMAL, Quality.DRAFT) else emptySet()
        if (qualities.isNotEmpty()) provenance[CapabilityKey.QUALITY] = Provenance.REPORTED_PJL

        provenance[CapabilityKey.MARGINS] = Provenance.ASSUMED

        return PrinterCapabilities(
            formats = formats,
            sides = sides,
            colorModes = colors,
            qualities = qualities,
            resolutionsDpi = resolutions,
            mediaSizes = sizes,
            defaultMedia = currentPaper,
            trays = trays,
            outputBins = allowed("OUTBIN"),
            maxCopies = copiesRange?.last,
            supportsPageRanges = false,
            orientations = setOf(Orientation.PORTRAIT, Orientation.LANDSCAPE),
            unprintableMargins = ASSUMED_MARGINS,
            provenance = provenance,
        )
    }
}
