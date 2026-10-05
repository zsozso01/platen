package io.github.zsozso01.platen.protocol.raster

import java.nio.ByteBuffer

/**
 * The fields of a PWG Raster page header (PWG 5102.4 table 1) that Platen reads or writes. Everything
 * else in the 1796-byte header is reserved and written as zero.
 */
public data class PwgPageHeader(
    /** Page bitmap width in pixels. The bitmap is the *full* page ("full bleed"); the printer clips. */
    val widthPixels: Int,
    val heightPixels: Int,
    val resolutionDpiX: Int,
    val resolutionDpiY: Int,
    val colorType: PwgColorType,
    /** Page size in points (1/72 inch). Derived from the bitmap size and resolution when null. */
    val pageSizePoints: Pair<Int, Int>? = null,
    /** PWG media name, e.g. `iso_a4_210x297mm`. Empty means "default media". */
    val pageSizeName: String = "",
    val mediaType: String = "",
    val mediaColor: String = "",
    val printContentOptimize: String = "",
    val renderingIntent: String = "",
    val duplex: Boolean = false,
    val tumble: Boolean = false,
    /** 0 means "printer default". */
    val numCopies: Int = 0,
    /** `MediaPositionEnum`: 0 auto, 1 main, 4 manual, 19 by-pass, 20+ tray N. */
    val mediaPosition: Int = 0,
    /** `PrintQualityEnum`: 0 default, 3 draft, 4 normal, 5 high. */
    val printQuality: Int = 0,
    /** 0 portrait, 1 landscape, 2 reverse portrait, 3 reverse landscape. */
    val orientation: Int = 0,
    /** 0 short edge first, 1 long edge first. */
    val leadingEdge: Int = 0,
    /** Total pages in the file, or 0 when unknown (streaming). */
    val totalPageCount: Int = 0,
    val crossFeedTransform: Int = 1,
    val feedTransform: Int = 1,
) {
    init {
        require(widthPixels in 1..MAX_DIMENSION && heightPixels in 1..MAX_DIMENSION) { "Bad bitmap size ${widthPixels}x$heightPixels" }
        require(resolutionDpiX in 1..MAX_DPI && resolutionDpiY in 1..MAX_DPI) { "Bad resolution ${resolutionDpiX}x$resolutionDpiY" }
        require(pageSizeName.length <= 63 && mediaType.length <= 63 && mediaColor.length <= 63 &&
            printContentOptimize.length <= 63 && renderingIntent.length <= 63) { "PWG header strings are limited to 63 characters" }
    }

    public val bytesPerLine: Int get() = colorType.bytesPerLine(widthPixels)

    internal fun encode(): ByteArray {
        val b = ByteBuffer.allocate(SIZE) // big-endian by default, zero-filled
        b.putCString(0, "PwgRaster")
        b.putCString(64, mediaColor)
        b.putCString(128, mediaType)
        b.putCString(192, printContentOptimize)
        b.putInt(272, if (duplex) 1 else 0)
        b.putInt(276, resolutionDpiX)
        b.putInt(280, resolutionDpiY)
        b.putInt(308, leadingEdge)
        b.putInt(324, mediaPosition)
        b.putInt(340, numCopies)
        b.putInt(344, orientation)
        val (pw, ph) = pageSizePoints ?: Pair(
            Math.round(widthPixels * 72.0 / resolutionDpiX).toInt(),
            Math.round(heightPixels * 72.0 / resolutionDpiY).toInt(),
        )
        b.putInt(352, pw)
        b.putInt(356, ph)
        b.putInt(368, if (tumble) 1 else 0)
        b.putInt(372, widthPixels)
        b.putInt(376, heightPixels)
        b.putInt(384, colorType.bitsPerColor)
        b.putInt(388, colorType.bitsPerPixel)
        b.putInt(392, bytesPerLine)
        b.putInt(396, 0) // chunky
        b.putInt(400, colorType.colorSpace)
        b.putInt(420, colorType.numColors)
        b.putInt(452, totalPageCount)
        b.putInt(456, crossFeedTransform)
        b.putInt(460, feedTransform)
        b.putInt(484, printQuality)
        b.putCString(1668, renderingIntent)
        b.putCString(1732, pageSizeName)
        return b.array()
    }

    internal companion object {
        const val SIZE = 1796
        const val MAX_DIMENSION = 1 shl 20 // ~1M pixels per side; bounds memory when reading hostile files
        const val MAX_DPI = 9600

        private fun ByteBuffer.putCString(offset: Int, text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            for (i in bytes.indices) put(offset + i, bytes[i])
        }

        /** Parses a header, or returns null with the reason in [error]. Never throws on bad content. */
        fun decode(bytes: ByteArray, error: (String) -> Unit): PwgPageHeader? {
            if (bytes.size < SIZE) return error("short page header").let { null }
            val b = ByteBuffer.wrap(bytes)
            fun cstring(offset: Int): String {
                var end = offset
                while (end < offset + 64 && b.get(end) != 0.toByte()) end++
                return String(bytes, offset, end - offset, Charsets.US_ASCII)
            }
            if (cstring(0) != "PwgRaster") return error("page header does not start with PwgRaster").let { null }
            val width = b.getInt(372)
            val height = b.getInt(376)
            val colorType = PwgColorType.fromHeader(b.getInt(384), b.getInt(388), b.getInt(400), b.getInt(420))
                ?: return error("unsupported colour type (bpc=${b.getInt(384)}, bpp=${b.getInt(388)}, cs=${b.getInt(400)})").let { null }
            if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION) return error("bad bitmap size ${width}x$height").let { null }
            val dpiX = b.getInt(276)
            val dpiY = b.getInt(280)
            if (dpiX !in 1..MAX_DPI || dpiY !in 1..MAX_DPI) return error("bad resolution ${dpiX}x$dpiY").let { null }
            if (b.getInt(392) != colorType.bytesPerLine(width)) return error("BytesPerLine does not match width and colour type").let { null }
            return PwgPageHeader(
                widthPixels = width,
                heightPixels = height,
                resolutionDpiX = dpiX,
                resolutionDpiY = dpiY,
                colorType = colorType,
                pageSizePoints = Pair(b.getInt(352), b.getInt(356)),
                pageSizeName = cstring(1732),
                mediaType = cstring(128),
                mediaColor = cstring(64),
                printContentOptimize = cstring(192),
                renderingIntent = cstring(1668),
                duplex = b.getInt(272) != 0,
                tumble = b.getInt(368) != 0,
                numCopies = b.getInt(340),
                mediaPosition = b.getInt(324),
                printQuality = b.getInt(484),
                orientation = b.getInt(344),
                leadingEdge = b.getInt(308),
                totalPageCount = b.getInt(452),
                crossFeedTransform = b.getInt(456),
                feedTransform = b.getInt(460),
            )
        }
    }
}
