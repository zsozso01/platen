package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.layout.Affine
import io.github.zsozso01.platen.core.layout.Side
import io.github.zsozso01.platen.core.model.Sides

/** How the user loads paper for the second pass of a manual duplex job. Needs a real printer to settle. */
public data class ManualDuplexOptions(
    /** Print the back faces last-to-first. Right for most output trays that stack pages face down. */
    val reverseBackOrder: Boolean = true,
    /** Turn the back faces half a turn, on top of what the binding already requires. */
    val rotateBacks: Boolean = false,
)

/**
 * One face ready for a raster writer.
 *
 * @property side the laid-out face with any back-side transform already applied, or null for a blank face
 * @property crossFeedTransform `1` normal, `-1` mirrored left to right (PWG header field)
 * @property feedTransform `1` normal, `-1` mirrored top to bottom (PWG header field)
 */
public class RasterFace(
    public val side: Side?,
    public val duplex: Boolean,
    public val tumble: Boolean,
    public val crossFeedTransform: Int = 1,
    public val feedTransform: Int = 1,
)

/** Works out, for each face of a pass, what the raster writer must be given. */
public object RasterFaces {
    /**
     * Back sides of printer-duplex jobs are produced in the printer's own coordinate system, which per
     * PWG 5102.4 table 9 depends on the binding and on the printer's `pwg-raster-document-sheet-back`.
     * Manual duplex back faces are turned for short-edge binding. Faces of the back pass come out
     * last-to-first when [ManualDuplexOptions.reverseBackOrder] is set.
     */
    public fun build(plan: PrintPlan, pass: Pass, options: ManualDuplexOptions = ManualDuplexOptions()): List<RasterFace> {
        val raster = checkNotNull(plan.raster) { "Not a raster plan" }
        val sheetW = plan.layout.sheet.widthPoints
        val sheetH = plan.layout.sheet.heightPoints
        val ordered = if (pass.role == Pass.Role.BACKS && options.reverseBackOrder) pass.faces.reversed() else pass.faces

        return ordered.mapIndexed { index, face ->
            val side = face.sideIndex?.let { plan.layout.sides[it] }
            when {
                raster.printerDuplex -> {
                    val isBack = index % 2 == 1
                    val (cross, feed) = if (isBack) backTransform(plan.printer.sides, raster.sheetBack) else 1 to 1
                    RasterFace(side?.let { applyTransform(it, cross, feed, sheetW, sheetH) }, duplex = true, tumble = raster.tumble, crossFeedTransform = cross, feedTransform = feed)
                }
                pass.role == Pass.Role.BACKS -> {
                    val shortEdge = plan.manualDuplex?.binding == Sides.TWO_SIDED_SHORT_EDGE
                    val turn = shortEdge xor options.rotateBacks
                    RasterFace(side?.let { if (turn) it.transformedBy(Affine.rotate180(sheetW, sheetH)) else it }, duplex = false, tumble = false)
                }
                else -> RasterFace(side, duplex = false, tumble = false)
            }
        }
    }

    /** PWG 5102.4 table 9: (CrossFeedTransform, FeedTransform) of a back side. */
    internal fun backTransform(sides: Sides, back: SheetBack): Pair<Int, Int> = when (sides) {
        Sides.ONE_SIDED -> 1 to 1
        Sides.TWO_SIDED_LONG_EDGE -> when (back) {
            SheetBack.FLIPPED -> 1 to -1
            SheetBack.MANUAL_TUMBLE -> 1 to 1
            SheetBack.NORMAL -> 1 to 1
            SheetBack.ROTATED -> -1 to -1
        }
        Sides.TWO_SIDED_SHORT_EDGE -> when (back) {
            SheetBack.FLIPPED -> -1 to 1
            SheetBack.MANUAL_TUMBLE -> -1 to -1
            SheetBack.NORMAL -> 1 to 1
            SheetBack.ROTATED -> 1 to 1
        }
    }

    private fun applyTransform(side: Side, cross: Int, feed: Int, w: Double, h: Double): Side = when {
        cross == -1 && feed == -1 -> side.transformedBy(Affine.rotate180(w, h))
        cross == -1 -> side.transformedBy(Affine.mirrorX(w))
        feed == -1 -> side.transformedBy(Affine.mirrorY(h))
        else -> side
    }
}
