package io.github.zsozso01.platen.protocol.ipp

import kotlin.math.roundToInt

/**
 * A size in PWG 5101.1 self-describing media naming, e.g. `iso_a4_210x297mm` or `na_letter_8.5x11in`.
 * Dimensions are stored in hundredths of a millimetre, the unit IPP uses in `media-size`.
 */
public data class PwgMediaSize(
    /** The full keyword, e.g. `iso_a4_210x297mm`. */
    val keyword: String,
    /** Class and name without the dimensions, e.g. `iso_a4`. */
    val name: String,
    val widthHundredthsMm: Int,
    val heightHundredthsMm: Int,
) {
    /** One end of a printer's custom paper size range. */
    public data class CustomLimit(val isMinimum: Boolean, val size: PwgMediaSize)

    public companion object {
        private val pattern = Regex("""^([a-z0-9]+)_([a-z0-9.\-]+)_([0-9]+(?:\.[0-9]+)?)x([0-9]+(?:\.[0-9]+)?)(mm|in)$""")

        /**
         * Parses a self-describing keyword. Returns null for anything else: vendor names, and the
         * `custom_min_`/`custom_max_` keywords, which describe the limits of the printer's custom size
         * range rather than a size (see [parseCustomLimit]).
         */
        public fun parse(keyword: String): PwgMediaSize? {
            val size = parseAny(keyword) ?: return null
            return size.takeUnless { it.name == "custom_min" || it.name == "custom_max" }
        }

        /** The smallest (`custom_min_`) or largest (`custom_max_`) custom size a printer accepts, or null. */
        public fun parseCustomLimit(keyword: String): CustomLimit? {
            val size = parseAny(keyword) ?: return null
            return when (size.name) {
                "custom_min" -> CustomLimit(isMinimum = true, size = size)
                "custom_max" -> CustomLimit(isMinimum = false, size = size)
                else -> null
            }
        }

        private fun parseAny(keyword: String): PwgMediaSize? {
            val m = pattern.matchEntire(keyword.trim()) ?: return null
            val w = m.groupValues[3].toDoubleOrNull() ?: return null
            val h = m.groupValues[4].toDoubleOrNull() ?: return null
            val toHundredthsMm = if (m.groupValues[5] == "in") 2540.0 else 100.0
            return PwgMediaSize(
                keyword = keyword.trim(),
                name = "${m.groupValues[1]}_${m.groupValues[2]}",
                widthHundredthsMm = (w * toHundredthsMm).roundToInt(),
                heightHundredthsMm = (h * toHundredthsMm).roundToInt(),
            )
        }
    }
}
