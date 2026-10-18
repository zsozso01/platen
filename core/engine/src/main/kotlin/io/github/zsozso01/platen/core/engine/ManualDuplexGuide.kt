package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.layout.SheetFaces
import io.github.zsozso01.platen.core.model.Sides

/**
 * The faces of this pass in the order the printer receives them. The back pass of a manual duplex job is sent
 * last-to-first when [ManualDuplexOptions.reverseBackOrder] is set; this is the only place that applies it.
 */
public fun Pass.inSendOrder(options: ManualDuplexOptions): List<Face> =
    if (role == Pass.Role.BACKS && options.reverseBackOrder) faces.reversed() else faces

/**
 * True when the plan puts pages on both sides of the paper: the printer's own duplexer, manual duplex,
 * or a layout that needs it (a booklet).
 */
public val PrintPlan.printsBothSides: Boolean
    get() = manualDuplex != null || printer.sides.isDuplex || layout.requiresDuplex

/** The sheets of this plan in print order, with the faces of each. */
public fun PrintPlan.sheets(): List<SheetFaces> = layout.sheets(printsBothSides)

/**
 * One sheet in the second pass of a manual duplex job, as the person reloading the pile meets it.
 *
 * @property back the side printed on this sheet's back, or null when nothing prints on it
 * @property front the side already printed on this sheet's front
 * @property otherEndFront the front of the sheet that would be at this position if the printer took the pile
 *   from the other end
 */
public data class ReloadSheet(val back: Int?, val front: Int, val otherEndFront: Int)

/**
 * What the person needs to know to reload the paper for the second pass of a manual duplex job.
 *
 * All numbers are indices into [io.github.zsozso01.platen.core.layout.LayoutPlan.sides]; use
 * [io.github.zsozso01.platen.core.layout.LayoutPlan.pagesLabel] to turn them into page numbers.
 *
 * @property binding which edge the sheets turn over on
 * @property firstFront the side printed first in the first pass
 * @property sheets the sheets in the order the printer takes them in the second pass
 */
public data class ReloadGuide(
    val binding: Sides,
    val firstFront: Int,
    val sheets: List<ReloadSheet>,
) {
    /** How many sheets go back in. */
    public val sheetCount: Int get() = sheets.size

    /** The sheet the printer takes first in the second pass. */
    public val firstSheet: ReloadSheet get() = sheets.first()

    /** The first sheet in the second pass that gets something printed on its back, or null if none does. */
    public val firstPrinted: ReloadSheet? get() = sheets.firstOrNull { it.back != null }

    /**
     * A sheet whose back would land behind a different front if the pile were taken from the other end, or
     * null when there is none. For explaining what a mix-up looks like.
     */
    public val mixUpExample: ReloadSheet?
        get() = sheets.firstOrNull { it.back != null && it.front != it.otherEndFront }

    /** The fronts (side indices) that have nothing on their back. */
    public val blankBackFronts: List<Int> get() = sheets.filter { it.back == null }.map { it.front }.distinct()
}

/**
 * The reload guide for the second pass, or null unless this plan is a manual duplex job with a fronts pass and a
 * backs pass of the same, non-zero size.
 *
 * A back whose side is blank (padding in a reversed layout, or the missing back of an odd page) is reported as
 * null: the sheet still goes through the printer, with nothing printed on it. With
 * [ManualDuplexOptions.reverseBackOrder] the printer takes the sheets from the other end of the pile.
 */
public fun PrintPlan.reloadGuide(options: ManualDuplexOptions = ManualDuplexOptions()): ReloadGuide? {
    val manual = manualDuplex ?: return null
    val fronts = passes.firstOrNull { it.role == Pass.Role.FRONTS }?.faces ?: return null
    val backs = passes.firstOrNull { it.role == Pass.Role.BACKS }?.faces ?: return null
    val n = fronts.size
    if (n == 0 || backs.size != n) return null

    // Fronts are never padding: padding only ever sits at an odd index, which is a back.
    fun front(sheet: Int) = checkNotNull(fronts[sheet].sideIndex) { "A front face cannot be blank" }
    val sheets = List(n) { position ->
        val sheet = if (options.reverseBackOrder) n - 1 - position else position
        ReloadSheet(
            back = backs[sheet].sideIndex?.takeUnless { layout.sides[it].isBlank },
            front = front(sheet),
            otherEndFront = front(n - 1 - sheet),
        )
    }
    return ReloadGuide(manual.binding, front(0), sheets)
}
