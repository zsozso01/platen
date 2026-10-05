package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.protocol.ipp.IppAttribute
import io.github.zsozso01.platen.protocol.ipp.IppAttributesBuilder
import io.github.zsozso01.platen.protocol.ipp.IppRange

/** Builds the IPP Job Template attributes for settings the printer should carry out. */
public object IppJobTicket {
    public fun build(settings: PrinterJobSettings): List<IppAttribute> = IppAttributesBuilder().apply {
        if (settings.copies > 1) integer("copies", settings.copies)

        // Always say which sides: a printer whose default is duplex must not surprise a one-sided job.
        keyword(
            "sides",
            when (settings.sides) {
                Sides.ONE_SIDED -> "one-sided"
                Sides.TWO_SIDED_LONG_EDGE -> "two-sided-long-edge"
                Sides.TWO_SIDED_SHORT_EDGE -> "two-sided-short-edge"
            },
        )

        when (settings.colorMode) {
            ColorMode.COLOR -> keyword("print-color-mode", "color")
            ColorMode.MONOCHROME -> keyword("print-color-mode", "monochrome")
            ColorMode.AUTO, null -> Unit
        }
        settings.quality?.let {
            enum(
                "print-quality",
                when (it) {
                    Quality.DRAFT -> 3
                    Quality.NORMAL -> 4
                    Quality.HIGH -> 5
                },
            )
        }
        settings.resolutionDpi?.let { resolution("printer-resolution", it, it) }

        media(settings)
        settings.outputBin?.let { keyword("output-bin", it) }

        settings.pageRanges?.takeIf { it.isNotEmpty() }?.let { ranges("page-ranges", it.map { r -> IppRange(r.first, r.last) }) }
        settings.numberUp?.let { integer("number-up", it) }
        settings.scaling?.let {
            keyword(
                "print-scaling",
                when (it) {
                    Scaling.ShrinkToFit -> "auto-fit"
                    Scaling.FitToPage -> "fit"
                    Scaling.Fill -> "fill"
                    Scaling.ActualSize -> "none"
                    is Scaling.Custom -> "none" // a custom zoom is applied by Platen, never asked of the printer
                },
            )
        }
        when (settings.orientation) {
            Orientation.PORTRAIT -> enum("orientation-requested", 3)
            Orientation.LANDSCAPE -> enum("orientation-requested", 4)
            Orientation.AUTO, null -> Unit
        }
        if (settings.bookletMaker) enum("finishings", 13)
    }.build()

    /**
     * Paper: a plain `media` keyword when only the size matters, otherwise a `media-col` with the size and
     * the requested tray and type (the two forms cannot be combined).
     */
    private fun IppAttributesBuilder.media(settings: PrinterJobSettings) {
        val paper = settings.paper
        val tray = settings.tray
        val type = settings.paperType
        if (tray == null && type == null) {
            paper?.let { p -> p.pwgName?.let { keyword("media", it) } ?: mediaCol(p.widthHundredthsMm, p.heightHundredthsMm, null, null) }
            return
        }
        mediaCol(paper?.widthHundredthsMm, paper?.heightHundredthsMm, tray?.id, type?.id)
    }

    private fun IppAttributesBuilder.mediaCol(widthHmm: Int?, heightHmm: Int?, source: String?, type: String?) {
        collection("media-col") {
            if (widthHmm != null && heightHmm != null) {
                collection("media-size") {
                    integer("x-dimension", widthHmm)
                    integer("y-dimension", heightHmm)
                }
            }
            source?.let { keyword("media-source", it) }
            type?.let { keyword("media-type", it) }
        }
    }

    /** Names of attributes in a rejected request, from the printer's `unsupported-attributes` group. */
    internal fun withoutAttributes(template: List<IppAttribute>, names: Set<String>): List<IppAttribute> {
        if (names.isEmpty()) return template
        return template.filterNot { attr ->
            attr.name in names ||
                // The printer may name a member of media-col ("media-col/media-type"); drop the whole collection.
                names.any { it.startsWith(attr.name + "/") || it.startsWith(attr.name + " ") }
        }
    }
}
