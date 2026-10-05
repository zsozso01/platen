package io.github.zsozso01.platen.route.pjl

import io.github.zsozso01.platen.core.model.MediaSize

/** PJL's names for paper sizes (the values of `@PJL SET PAPER=`) and their dimensions. */
internal object PjlPaper {
    private val sizes: Map<String, MediaSize> = mapOf(
        "LETTER" to MediaSize(21590, 27940, "na_letter_8.5x11in"),
        "LEGAL" to MediaSize(21590, 35560, "na_legal_8.5x14in"),
        "EXECUTIVE" to MediaSize(18415, 26670, "na_executive_7.25x10.5in"),
        "LEDGER" to MediaSize(27940, 43180, "na_ledger_11x17in"),
        "A3" to MediaSize(29700, 42000, "iso_a3_297x420mm"),
        "A4" to MediaSize(21000, 29700, "iso_a4_210x297mm"),
        "A5" to MediaSize(14800, 21000, "iso_a5_148x210mm"),
        "A6" to MediaSize(10500, 14800, "iso_a6_105x148mm"),
        "B5" to MediaSize(17600, 25000, "iso_b5_176x250mm"),
        "JISB4" to MediaSize(25700, 36400, "jis_b4_257x364mm"),
        "JISB5" to MediaSize(18200, 25700, "jis_b5_182x257mm"),
    )

    fun toMedia(pjlName: String): MediaSize? = sizes[pjlName.trim().uppercase()]

    fun nameFor(size: MediaSize): String? = sizes.entries.firstOrNull { it.value.samePaperAs(size) }?.key
}
