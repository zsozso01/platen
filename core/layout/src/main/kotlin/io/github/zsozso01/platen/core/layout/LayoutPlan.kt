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
public data class Side(val placements: List<Placement>)

/**
 * The result of imposing a document on sheets: what goes where, in print order. Consumed by both the
 * raster path (draw into a bitmap) and the vector-PDF path (emit a PDF page with form XObjects).
 */
public data class LayoutPlan(
    /** The sheet as fed to the printer (its native width and height). */
    val sheet: MediaSize,
    /** The area where content may land, after unprintable and user margins (sheet points). */
    val printableArea: Rect,
    /** Printed faces in order. With duplex printing, consecutive pairs are the front and back of one sheet. */
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
