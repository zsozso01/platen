package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.layout.PageGeometry
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.Margins
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import io.github.zsozso01.platen.core.model.PrinterScaling
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.RasterProfile
import io.github.zsozso01.platen.core.model.Sides
import java.io.ByteArrayInputStream
import java.io.InputStream

/** A document made of blank pages of given sizes; optionally a PDF that can be sent unchanged. */
class SyntheticDocument(
    private val pages: List<PageGeometry>,
    override val name: String = "test.pdf",
    override val pdf: PdfFile? = null,
) : DocumentSource {
    override val pageCount: Int get() = pages.size

    override fun pageGeometry(pageIndex: Int): PageGeometry = pages[pageIndex]

    companion object {
        private val a4 = PageGeometry(MediaSize.A4.widthPoints, MediaSize.A4.heightPoints)

        fun a4Pages(count: Int, asPdf: Boolean = true) = SyntheticDocument(
            List(count) { a4 },
            pdf = if (asPdf) object : PdfFile {
                override val length: Long = 8
                override fun open(): InputStream = ByteArrayInputStream("%PDF-1.7".toByteArray())
            } else null,
        )
    }
}

/** Capabilities shaped like a DeskJet 3700 as documented: raster formats only, simplex, 300 dpi, fixed margins. */
val inkjetCaps = PrinterCapabilities(
    formats = listOf(DocumentFormat.PWG_RASTER, DocumentFormat.URF, DocumentFormat.PCLM, DocumentFormat.JPEG),
    sides = setOf(Sides.ONE_SIDED),
    colorModes = setOf(ColorMode.AUTO, ColorMode.COLOR, ColorMode.MONOCHROME),
    qualities = setOf(Quality.DRAFT, Quality.NORMAL, Quality.HIGH),
    resolutionsDpi = listOf(300),
    mediaSizes = listOf(MediaSize.A4, MediaSize.LETTER, MediaSize.LEGAL, MediaSize.A5),
    defaultMedia = MediaSize.A4,
    trays = listOf(MediaSource("main")),
    outputBins = listOf("face-up"),
    maxCopies = 99,
    supportsPageRanges = true,
    unprintableMargins = Margins(top = 296, right = 296, bottom = 1270, left = 296),
    raster = RasterProfile(colorTypes = listOf("sgray_8", "srgb_8"), resolutionsDpi = listOf(300), sheetBack = "rotated"),
)

/** Capabilities shaped like a modern PDF laser: native PDF, duplex, trays, 600 dpi, several layout features. */
val laserCaps = PrinterCapabilities(
    formats = listOf(DocumentFormat.PDF, DocumentFormat.POSTSCRIPT, DocumentFormat.PWG_RASTER),
    sides = setOf(Sides.ONE_SIDED, Sides.TWO_SIDED_LONG_EDGE, Sides.TWO_SIDED_SHORT_EDGE),
    colorModes = setOf(ColorMode.MONOCHROME),
    qualities = setOf(Quality.DRAFT, Quality.NORMAL, Quality.HIGH),
    resolutionsDpi = listOf(300, 600),
    mediaSizes = listOf(MediaSize.A4, MediaSize.LETTER, MediaSize.A5, MediaSize.A3),
    defaultMedia = MediaSize.A4,
    trays = listOf(MediaSource("tray-1"), MediaSource("tray-2"), MediaSource("manual")),
    outputBins = listOf("face-down"),
    maxCopies = 999,
    supportsPageRanges = true,
    numberUp = setOf(1, 2, 4, 6, 9, 16),
    printerScaling = setOf(PrinterScaling.AUTO, PrinterScaling.FIT, PrinterScaling.FILL, PrinterScaling.NONE),
    raster = RasterProfile(colorTypes = listOf("sgray_8"), resolutionsDpi = listOf(300, 600)),
)
