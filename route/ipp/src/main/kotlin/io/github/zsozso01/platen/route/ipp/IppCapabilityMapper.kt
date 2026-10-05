package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.core.model.CapabilityKey
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.MediaType
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.PrinterScaling
import io.github.zsozso01.platen.core.model.Provenance
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.RasterProfile
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.core.model.Supply
import io.github.zsozso01.platen.protocol.ipp.IppFormats
import io.github.zsozso01.platen.protocol.ipp.IppPrinterAttributes
import io.github.zsozso01.platen.protocol.ipp.IppResolution

/** Turns a `Get-Printer-Attributes` answer into [PrinterCapabilities]. Missing attributes stay unreported, never invented. */
public object IppCapabilityMapper {
    public fun map(attrs: IppPrinterAttributes): PrinterCapabilities {
        val provenance = mutableMapOf<CapabilityKey, Provenance>()
        fun reported(key: CapabilityKey, present: Boolean) {
            if (present) provenance[key] = Provenance.REPORTED_IPP
        }

        val formats = attrs.documentFormats.map(::DocumentFormat)
        reported(CapabilityKey.FORMATS, formats.isNotEmpty())

        val sides = attrs.sidesSupported.mapNotNull {
            when (it) {
                "one-sided" -> Sides.ONE_SIDED
                "two-sided-long-edge" -> Sides.TWO_SIDED_LONG_EDGE
                "two-sided-short-edge" -> Sides.TWO_SIDED_SHORT_EDGE
                else -> null
            }
        }.toSet()
        reported(CapabilityKey.SIDES, sides.isNotEmpty())

        val colorModes = attrs.colorModesSupported.mapNotNull {
            when (it) {
                "color" -> ColorMode.COLOR
                "monochrome", "auto-monochrome", "process-monochrome", "bi-level", "process-bi-level" -> ColorMode.MONOCHROME
                "auto" -> ColorMode.AUTO
                else -> null
            }
        }.toSet()
        reported(CapabilityKey.COLOR, colorModes.isNotEmpty())

        val qualities = attrs.qualitiesSupported.mapNotNull {
            when (it) {
                3 -> Quality.DRAFT
                4 -> Quality.NORMAL
                5 -> Quality.HIGH
                else -> null
            }
        }.toSet()
        reported(CapabilityKey.QUALITY, qualities.isNotEmpty())

        val resolutions = attrs.resolutionsSupported.mapNotNull(IppResolution::dpiOrNull).distinct().sorted()
        reported(CapabilityKey.RESOLUTION, resolutions.isNotEmpty())

        val sizes = mediaSizes(attrs)
        val defaultMedia = attrs.mediaDefault?.let { keyword -> sizes.firstOrNull { it.pwgName == keyword } }
        reported(CapabilityKey.MEDIA, sizes.isNotEmpty())

        val trays = attrs.mediaSourcesSupported.map(::MediaSource)
        reported(CapabilityKey.TRAYS, trays.isNotEmpty())

        reported(CapabilityKey.COPIES, attrs.copiesSupported != null)
        reported(CapabilityKey.PAGE_RANGES, attrs.pageRangesSupported != null)

        val numberUp = (attrs.numberUpSupported + 1).toSet()
        reported(CapabilityKey.NUMBER_UP, attrs.numberUpSupported.isNotEmpty())

        val scaling = attrs.printScalingSupported.mapNotNull {
            when (it) {
                "auto" -> PrinterScaling.AUTO
                "auto-fit" -> PrinterScaling.SHRINK_TO_FIT
                "fit" -> PrinterScaling.FIT
                "fill" -> PrinterScaling.FILL
                "none" -> PrinterScaling.NONE
                else -> null
            }
        }.toSet()
        reported(CapabilityKey.SCALING, scaling.isNotEmpty())

        val finishings = attrs.finishingsSupported
        reported(CapabilityKey.FINISHING, finishings.isNotEmpty())

        val orientations = attrs.orientationsSupported.flatMap {
            when (it) {
                3, 6 -> listOf(Orientation.PORTRAIT)
                4, 5 -> listOf(Orientation.LANDSCAPE)
                else -> emptyList()
            }
        }.toSet()
        reported(CapabilityKey.ORIENTATION, orientations.isNotEmpty())

        val margins = margins(attrs)
        reported(CapabilityKey.MARGINS, margins != null)

        val raster = if (formats.any { it.mime.equals(IppFormats.PWG_RASTER, ignoreCase = true) }) {
            RasterProfile(
                colorTypes = attrs.pwgRasterTypes,
                resolutionsDpi = attrs.pwgRasterResolutions.mapNotNull(IppResolution::dpiOrNull).distinct().sorted(),
                sheetBack = attrs.pwgRasterSheetBack,
            )
        } else {
            null
        }
        reported(CapabilityKey.RASTER, raster != null && (raster.colorTypes.isNotEmpty() || raster.resolutionsDpi.isNotEmpty()))

        return PrinterCapabilities(
            formats = formats,
            sides = sides.ifEmpty { setOf(Sides.ONE_SIDED) },
            colorModes = colorModes.ifEmpty { setOf(ColorMode.AUTO) },
            qualities = qualities,
            resolutionsDpi = resolutions,
            mediaSizes = sizes,
            defaultMedia = defaultMedia,
            trays = trays,
            mediaTypes = attrs.mediaTypesSupported.map(::MediaType),
            outputBins = attrs.outputBinsSupported,
            maxCopies = attrs.copiesSupported?.upper,
            supportsPageRanges = attrs.pageRangesSupported == true,
            numberUp = numberUp,
            printerScaling = scaling,
            bookletMaker = BOOKLET_MAKER in finishings,
            orientations = orientations.ifEmpty { setOf(Orientation.PORTRAIT) },
            unprintableMargins = margins,
            raster = raster,
            supplies = supplies(attrs),
            provenance = provenance,
        )
    }

    private const val BOOKLET_MAKER = 13

    /** Sizes from `media-supported` keywords, plus any in `media-col-database` that have an explicit size. */
    private fun mediaSizes(attrs: IppPrinterAttributes): List<MediaSize> {
        val fromKeywords = attrs.mediaSizes.map { MediaSize(it.widthHundredthsMm, it.heightHundredthsMm, it.keyword) }
        val fromDatabase = attrs.mediaColDatabase.mapNotNull { col ->
            val size = col["media-size"]?.collections()?.firstOrNull() ?: return@mapNotNull null
            val x = size["x-dimension"]?.int() ?: return@mapNotNull null
            val y = size["y-dimension"]?.int() ?: return@mapNotNull null
            if (x <= 0 || y <= 0) null else MediaSize(x, y, null)
        }
        return (fromKeywords + fromDatabase.filter { db -> fromKeywords.none { it.samePaperAs(db) } }).distinctBy { it.widthHundredthsMm to it.heightHundredthsMm }
    }

    /**
     * The printer lists the margin values it supports (e.g. `0` for borderless and `296` otherwise). The
     * largest is the border to respect by default, so content is never placed where it would be clipped.
     */
    private fun margins(attrs: IppPrinterAttributes): Margins? {
        val top = attrs.mediaTopMarginsSupported.maxOrNull()
        val right = attrs.mediaRightMarginsSupported.maxOrNull()
        val bottom = attrs.mediaBottomMarginsSupported.maxOrNull()
        val left = attrs.mediaLeftMarginsSupported.maxOrNull()
        if (top == null && right == null && bottom == null && left == null) return null
        return Margins(top ?: 0, right ?: 0, bottom ?: 0, left ?: 0)
    }

    private fun supplies(attrs: IppPrinterAttributes): List<Supply> {
        val names = attrs.markerNames
        val levels = attrs.markerLevels
        val colors = attrs.markerColors
        val low = attrs.markerLowLevels
        return names.indices.map { i ->
            val level = levels.getOrNull(i)?.takeIf { it in 0..100 }
            Supply(
                name = names[i],
                levelPercent = level,
                colorHex = colors.getOrNull(i)?.takeIf { it.startsWith("#") },
                isLow = level != null && low.getOrNull(i)?.let { level <= it } == true,
            )
        }
    }
}
