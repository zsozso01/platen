package io.github.zsozso01.platen.core.layout

import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.Sides

/** One source page placed on a printed side. */
public data class Placement(
    /** 1-based page number in the source document, or null for a deliberately blank cell (booklet padding). */
    val sourcePage: Int?,
    /** Maps source-page points to sheet points. */
    val transform: Affine,
    /** Content outside this rectangle (sheet points) must not be drawn. */
    val clip: Rect,
    /** Where the scaled page lands on the sheet, for previews and borders. */
    val bounds: Rect,
    val drawBorder: Boolean,
    /** The scale applied, relative to the page's own size. 1.0 is actual size. */
    val scale: Double,
)

/** One face of one sheet. */
public data class Side(val placements: List<Placement>) {
    /** The source pages on this face, in cell order. Empty for a blank face. */
    public val sourcePages: List<Int> get() = placements.mapNotNull { it.sourcePage }

    /** True when no page lands on this face: padding that keeps two-sided sheets paired, or a missing back. */
    public val isBlank: Boolean get() = sourcePages.isEmpty()

    /**
     * The pages on this face for people to read: "3", "1–4" for a run of consecutive pages, "1–3, 7" for several
     * runs, "8, 1" for a booklet spread. Empty for a blank face.
     */
    public fun pagesLabel(): String {
        val runs = mutableListOf<IntRange>()
        for (page in sourcePages) {
            val last = runs.lastOrNull()
            if (last != null && page == last.last + 1) runs[runs.lastIndex] = last.first..page else runs += page..page
        }
        return runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
    }

    /** The same face after [transform] is applied to the whole sheet (a mirror or half turn for duplex back sides). */
    public fun transformedBy(transform: Affine): Side = Side(
        placements.map {
            it.copy(
                transform = transform.after(it.transform),
                clip = transform.mapBounds(it.clip),
                bounds = transform.mapBounds(it.bounds),
            )
        },
    )
}

/**
 * The two faces of one sheet of paper, as indices into [LayoutPlan.sides].
 *
 * @property front the face printed first
 * @property back the face on the other side of the paper, or null when nothing is printed on it
 */
public data class SheetFaces(val front: Int, val back: Int?)

/**
 * The result of imposing a document on sheets: what goes where, in print order. Consumed by both the
 * raster path (draw into a bitmap) and the vector-PDF path (emit a PDF page with form XObjects).
 */
public data class LayoutPlan(
    /** The sheet as fed to the printer (its native width and height). */
    val sheet: MediaSize,
    /** The area where content may land, after unprintable and user margins (sheet points). */
    val printableArea: Rect,
    /**
     * Printed faces in order. With two-sided printing, consecutive pairs (0 and 1, 2 and 3, ...) are the
     * front and back of one sheet. A reversed two-sided layout may hold a blank side so the pairs stay aligned.
     */
    val sides: List<Side>,
    /** True when the layout only makes sense printed double-sided (booklets). */
    val requiresDuplex: Boolean,
    /** The binding the printer must use when [requiresDuplex]; otherwise null. */
    val requiredSides: Sides?,
    val notes: List<Note>,
) {
    public val isEmpty: Boolean get() = sides.isEmpty()

    /** Number of physical sheets when printed with [duplex] on or off. */
    public fun sheetCount(duplex: Boolean): Int = if (duplex) (sides.size + 1) / 2 else sides.size

    /**
     * The sheets in print order, each with the indices of its faces in [sides]. Two-sided: (0, 1), (2, 3), and
     * so on, the last back being null when the count is odd. One-sided: every side is a sheet of its own.
     */
    public fun sheets(twoSided: Boolean): List<SheetFaces> =
        if (twoSided) {
            sides.indices.step(2).map { SheetFaces(it, (it + 1).takeIf { back -> back < sides.size }) }
        } else {
            sides.indices.map { SheetFaces(it, null) }
        }

    /** The [Side.pagesLabel] of the side at [sideIndex], or null when there is no such side or it is blank. */
    public fun pagesLabel(sideIndex: Int?): String? =
        sideIndex?.let { sides[it] }?.takeUnless { it.isBlank }?.pagesLabel()

    /** Things the user may want to know about the layout. */
    public enum class Note {
        /** Content is cropped by Fill or an oversized custom scale. */
        CONTENT_CROPPED,

        /** Pages were turned sideways to fit better. */
        PAGES_ROTATED,

        /** The selection is empty: nothing would be printed. */
        NOTHING_SELECTED,

        /** Booklet needed blank pages to reach a multiple of four. */
        BOOKLET_PADDED,
    }
}
