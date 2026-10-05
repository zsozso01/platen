package io.github.zsozso01.platen.protocol.raster

/**
 * A page bitmap colour configuration: one value of the IPP attribute
 * `pwg-raster-document-type-supported` (PWG 5102.4 table 12) and the page header fields it implies.
 */
public enum class PwgColorType(
    /** The IPP keyword, e.g. `srgb_8`. */
    public val keyword: String,
    public val bitsPerColor: Int,
    public val bitsPerPixel: Int,
    /** `ColorSpaceEnum` from table 3 of the spec. */
    public val colorSpace: Int,
    public val numColors: Int,
) {
    BLACK_1("black_1", 1, 1, 3, 1),
    SGRAY_1("sgray_1", 1, 1, 18, 1),
    BLACK_8("black_8", 8, 8, 3, 1),
    SGRAY_8("sgray_8", 8, 8, 18, 1),
    SRGB_8("srgb_8", 8, 24, 19, 3),
    RGB_8("rgb_8", 8, 24, 1, 3),
    ADOBE_RGB_8("adobe-rgb_8", 8, 24, 20, 3),
    CMYK_8("cmyk_8", 8, 32, 6, 4),
    SGRAY_16("sgray_16", 16, 16, 18, 1),
    SRGB_16("srgb_16", 16, 48, 19, 3),
    RGB_16("rgb_16", 16, 48, 1, 3),
    ADOBE_RGB_16("adobe-rgb_16", 16, 48, 20, 3),
    ;

    /** Bytes in one uncompressed line of [widthPixels] pixels: `(bitsPerPixel * width + 7) / 8`. */
    public fun bytesPerLine(widthPixels: Int): Int = ((bitsPerPixel.toLong() * widthPixels + 7) / 8).toInt()

    /** Size of one unit of run-length compression: a whole pixel, or one packed byte for 1-bit data. */
    internal val compressionUnitBytes: Int get() = if (bitsPerPixel < 8) 1 else bitsPerPixel / 8

    public companion object {
        public fun fromKeyword(keyword: String): PwgColorType? = entries.firstOrNull { it.keyword.equals(keyword.trim(), ignoreCase = true) }

        internal fun fromHeader(bitsPerColor: Int, bitsPerPixel: Int, colorSpace: Int, numColors: Int): PwgColorType? =
            entries.firstOrNull {
                it.bitsPerColor == bitsPerColor && it.bitsPerPixel == bitsPerPixel && it.colorSpace == colorSpace && it.numColors == numColors
            }
    }
}
