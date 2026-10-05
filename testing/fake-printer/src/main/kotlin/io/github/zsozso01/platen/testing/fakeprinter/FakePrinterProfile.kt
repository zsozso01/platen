package io.github.zsozso01.platen.testing.fakeprinter

import io.github.zsozso01.platen.protocol.ipp.IppAttribute
import io.github.zsozso01.platen.protocol.ipp.IppAttributesBuilder
import io.github.zsozso01.platen.protocol.ipp.IppEnum
import io.github.zsozso01.platen.protocol.ipp.IppInteger
import io.github.zsozso01.platen.protocol.ipp.IppRange
import io.github.zsozso01.platen.protocol.ipp.IppResolution
import io.github.zsozso01.platen.protocol.ipp.IppString

private val dpi = IppResolution.Units.DOTS_PER_INCH

private fun IppAttributesBuilder.mimeTypes(name: String, values: List<String>) =
    addAll(name, values.map { IppString(IppString.Kind.MIME_MEDIA_TYPE, it) })

private fun IppAttributesBuilder.enums(name: String, values: List<Int>) = addAll(name, values.map(::IppEnum))

private fun IppAttributesBuilder.integers(name: String, values: List<Int>) = addAll(name, values.map(::IppInteger))

/**
 * The attributes a fake printer reports. Profiles are written from publicly documented capability
 * sets so tests exercise the same shapes real printers produce.
 */
class FakePrinterProfile(
    val name: String,
    val documentFormats: List<String>,
    val sides: List<String>,
    private val extraAttributes: IppAttributesBuilder.() -> Unit,
) {
    fun printerAttributes(uri: String): List<IppAttribute> = IppAttributesBuilder().apply {
        keywords("ipp-versions-supported", listOf("1.1", "2.0"))
        uri("printer-uri-supported", uri)
        keywords("uri-security-supported", listOf("none"))
        keywords("uri-authentication-supported", listOf("none"))
        text("printer-make-and-model", name)
        name("printer-name", name)
        enum("printer-state", 3)
        keywords("printer-state-reasons", listOf("none"))
        boolean("printer-is-accepting-jobs", true)
        mimeTypes("document-format-supported", documentFormats)
        mimeMediaType("document-format-default", "application/octet-stream")
        keywords("sides-supported", sides)
        keyword("sides-default", sides.first())
        // Not `build()`: inside this scope that would resolve to IppAttributesBuilder.build().
        extraAttributes()
    }.build()

    companion object {
        /**
         * Shaped after the publicly documented IPP attributes of HP DeskJet 3700-series inkjets:
         * no PDF, raster formats only, 300 dpi, simplex, portrait only, fixed unprintable margins.
         */
        val inkjetRasterOnly = FakePrinterProfile(
            name = "Fake Inkjet 3700 series",
            documentFormats = listOf(
                "application/vnd.hp-PCL", "image/jpeg", "application/PCLm", "image/urf", "image/pwg-raster", "application/octet-stream",
            ),
            sides = listOf("one-sided"),
        ) {
            keywords("print-color-mode-supported", listOf("auto", "monochrome", "color"))
            keyword("print-color-mode-default", "color")
            enums("print-quality-supported", listOf(3, 4, 5))
            enum("print-quality-default", 4)
            add("printer-resolution-supported", IppResolution(300, 300, dpi))
            add("copies-supported", IppRange(1, 99))
            boolean("page-ranges-supported", true)
            enums("orientation-requested-supported", listOf(3))
            keywords("print-scaling-supported", listOf("auto", "auto-fit", "fill", "fit", "none"))
            keywords("media-supported", listOf("na_letter_8.5x11in", "na_legal_8.5x14in", "iso_a5_148x210mm", "iso_a4_210x297mm"))
            keyword("media-default", "iso_a4_210x297mm")
            keywords("media-ready", listOf("iso_a4_210x297mm"))
            keywords("media-source-supported", listOf("main"))
            keywords("output-bin-supported", listOf("face-up"))
            integers("media-bottom-margin-supported", listOf(1270))
            integers("media-top-margin-supported", listOf(296))
            integers("media-left-margin-supported", listOf(296))
            integers("media-right-margin-supported", listOf(296))
            keywords("urf-supported", listOf("CP1", "PQ3-4-5", "RS300", "SRGB24", "W8", "V1.4"))
            keywords("pwg-raster-document-type-supported", listOf("sgray_8", "srgb_8"))
            add("pwg-raster-document-resolution-supported", IppResolution(300, 300, dpi))
            keyword("pwg-raster-document-sheet-back", "rotated")
            keywords("marker-names", listOf("tri-color ink", "black ink"))
            integers("marker-levels", listOf(90, 50))
        }

        /** A modern laser: native PDF, duplex, trays, 600 dpi. */
        val laserPdf = FakePrinterProfile(
            name = "Fake LaserJet PDF",
            documentFormats = listOf(
                "application/pdf", "application/postscript", "application/vnd.hp-PCL", "image/urf", "image/pwg-raster", "application/octet-stream",
            ),
            sides = listOf("one-sided", "two-sided-long-edge", "two-sided-short-edge"),
        ) {
            keywords("print-color-mode-supported", listOf("monochrome"))
            keyword("print-color-mode-default", "monochrome")
            enums("print-quality-supported", listOf(3, 4, 5))
            addAll("printer-resolution-supported", listOf(IppResolution(300, 300, dpi), IppResolution(600, 600, dpi)))
            add("copies-supported", IppRange(1, 999))
            boolean("page-ranges-supported", true)
            keywords("media-supported", listOf("na_letter_8.5x11in", "iso_a4_210x297mm", "iso_a5_148x210mm", "iso_a3_297x420mm"))
            keyword("media-default", "iso_a4_210x297mm")
            keywords("media-source-supported", listOf("auto", "tray-1", "tray-2", "manual"))
            keywords("output-bin-supported", listOf("face-down"))
            keywords("urf-supported", listOf("CP1", "DM1", "RS300-600", "W8"))
            keywords("pwg-raster-document-type-supported", listOf("sgray_8"))
            addAll("pwg-raster-document-resolution-supported", listOf(IppResolution(300, 300, dpi), IppResolution(600, 600, dpi)))
        }
    }
}
