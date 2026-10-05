package io.github.zsozso01.platen.core.model

/** A paper size in hundredths of a millimetre, the unit IPP and the PWG media names use. */
public data class MediaSize(
    val widthHundredthsMm: Int,
    val heightHundredthsMm: Int,
    /** PWG media keyword such as `iso_a4_210x297mm`, when the size has one. */
    val pwgName: String? = null,
) {
    init {
        require(widthHundredthsMm > 0 && heightHundredthsMm > 0) { "Media size must be positive" }
    }

    public val widthMm: Double get() = widthHundredthsMm / 100.0
    public val heightMm: Double get() = heightHundredthsMm / 100.0

    /** Width in PostScript points (1/72 inch). */
    public val widthPoints: Double get() = widthHundredthsMm * POINTS_PER_HUNDREDTH_MM
    public val heightPoints: Double get() = heightHundredthsMm * POINTS_PER_HUNDREDTH_MM

    public fun widthPixels(dpi: Int): Int = Math.round(widthPoints * dpi / 72.0).toInt()

    public fun heightPixels(dpi: Int): Int = Math.round(heightPoints * dpi / 72.0).toInt()

    /** The same paper turned 90 degrees. */
    public fun rotated(): MediaSize = MediaSize(heightHundredthsMm, widthHundredthsMm, pwgName)

    public val isLandscape: Boolean get() = widthHundredthsMm > heightHundredthsMm

    /** Same physical paper, ignoring which way round it is and ignoring the name (within 0.5 mm). */
    public fun samePaperAs(other: MediaSize, toleranceHundredthsMm: Int = 50): Boolean {
        fun close(a: Int, b: Int) = kotlin.math.abs(a - b) <= toleranceHundredthsMm
        return (close(widthHundredthsMm, other.widthHundredthsMm) && close(heightHundredthsMm, other.heightHundredthsMm)) ||
            (close(widthHundredthsMm, other.heightHundredthsMm) && close(heightHundredthsMm, other.widthHundredthsMm))
    }

    public companion object {
        private const val POINTS_PER_HUNDREDTH_MM = 72.0 / 2540.0

        public val A4: MediaSize = MediaSize(21000, 29700, "iso_a4_210x297mm")
        public val A5: MediaSize = MediaSize(14800, 21000, "iso_a5_148x210mm")
        public val A3: MediaSize = MediaSize(29700, 42000, "iso_a3_297x420mm")
        public val LETTER: MediaSize = MediaSize(21590, 27940, "na_letter_8.5x11in")
        public val LEGAL: MediaSize = MediaSize(21590, 35560, "na_legal_8.5x14in")
    }
}

/** The part of the sheet the printer cannot print on, in hundredths of a millimetre. */
public data class Margins(
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
) {
    init {
        require(top >= 0 && right >= 0 && bottom >= 0 && left >= 0) { "Margins cannot be negative" }
    }

    public companion object {
        public val NONE: Margins = Margins(0, 0, 0, 0)

        /** A margin the same on every side. */
        public fun uniform(hundredthsMm: Int): Margins = Margins(hundredthsMm, hundredthsMm, hundredthsMm, hundredthsMm)
    }
}

/** An input tray or feeder. [id] is the protocol's own name (an IPP keyword such as `tray-1`). */
public data class MediaSource(val id: String)

/** A paper type, e.g. `stationery` or `photographic-glossy`. [id] is the protocol's own name. */
public data class MediaType(val id: String)
