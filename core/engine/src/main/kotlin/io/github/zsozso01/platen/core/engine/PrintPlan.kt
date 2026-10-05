package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.layout.LayoutPlan
import io.github.zsozso01.platen.core.model.ColorMode
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.MediaSource
import io.github.zsozso01.platen.core.model.MediaType
import io.github.zsozso01.platen.core.model.Orientation
import io.github.zsozso01.platen.core.model.Quality
import io.github.zsozso01.platen.core.model.Scaling
import io.github.zsozso01.platen.core.model.Sides

/** The facets of a job the planner makes a decision about. */
public enum class SettingKind {
    COPIES, COLLATION, SIDES, PAGES, REVERSE, PAPER, PAPER_TYPE, TRAY, COLOR, QUALITY, RESOLUTION,
    SCALING, ORIENTATION, MARGINS, PAGES_PER_SHEET, BOOKLET, OUTPUT_BIN, ECONOMY,
}

/** Who carries out a setting, or why it cannot be carried out. Shown to the user so nothing is silent. */
public data class Decision(val setting: SettingKind, val outcome: Outcome, val detail: String? = null) {
    public enum class Outcome {
        /** The printer does it, as asked. */
        BY_PRINTER,

        /** Platen does it before sending (always exact). */
        BY_APP,

        /** Done, but not exactly as asked (a nearby value was used). */
        APPROXIMATED,

        /** Could not be done; the user should be told before printing. */
        IGNORED,
    }
}

/** Settings handed to the job protocol for the *printer* to carry out. `null` means "leave to the printer". */
public data class PrinterJobSettings(
    val jobName: String,
    val copies: Int = 1,
    val sides: Sides = Sides.ONE_SIDED,
    val colorMode: ColorMode? = null,
    val quality: Quality? = null,
    val resolutionDpi: Int? = null,
    val paper: MediaSize? = null,
    val paperType: MediaType? = null,
    val tray: MediaSource? = null,
    val outputBin: String? = null,
    /** Ranges the printer should print (pass-through only), 1-based, ascending and disjoint. */
    val pageRanges: List<IntRange>? = null,
    val numberUp: Int? = null,
    val scaling: Scaling? = null,
    val orientation: Orientation? = null,
    /** Ask the printer to finish as a booklet. */
    val bookletMaker: Boolean = false,
    /** Toner-saving requested (only meaningful where the protocol has such a mode). */
    val economy: Boolean = false,
)

/** How the back side of a duplex page must be oriented for a raster printer (PWG 5102.4, `pwg-raster-document-sheet-back`). */
public enum class SheetBack {
    NORMAL, FLIPPED, ROTATED, MANUAL_TUMBLE;

    public companion object {
        public fun fromKeyword(keyword: String?): SheetBack = when (keyword?.lowercase()) {
            "flipped" -> FLIPPED
            "rotated" -> ROTATED
            "manual-tumble" -> MANUAL_TUMBLE
            else -> NORMAL
        }
    }
}

/** Parameters for producing a raster document. */
public data class RasterParams(
    val dpi: Int,
    val pixelFormat: RasterPixelFormat,
    /** IPP `pwg-raster-document-type-supported` keyword matching [pixelFormat]. */
    val pwgType: String,
    /** True when the printer, not Platen, prints on both sides (the header says Duplex). */
    val printerDuplex: Boolean,
    val tumble: Boolean,
    val sheetBack: SheetBack,
)

/**
 * One printed face in a [Pass]: a side of the [io.github.zsozso01.platen.core.layout.LayoutPlan], or
 * a deliberately blank face (padding so duplex sheets pair up, or a missing back).
 */
public data class Face(val sideIndex: Int?) {
    public val isBlank: Boolean get() = sideIndex == null
}

/**
 * Faces sent to the printer as one job. Usually one pass. Manual duplex has two: the front faces, then
 * (after the user reloads the paper) the back faces.
 */
public data class Pass(val faces: List<Face>, val role: Role = Role.ALL) {
    public enum class Role { ALL, FRONTS, BACKS }
}

/** Marks a plan that prints both sides by hand: the engine pauses between the two passes. */
public data class ManualDuplex(val binding: Sides)

/** The route chosen for one job. */
public enum class RouteKind { PASS_THROUGH, RASTER }

/** Everything decided for one job on one printer. */
public data class PrintPlan(
    val route: RouteKind,
    /** What is sent to the printer. */
    val format: DocumentFormat,
    val layout: LayoutPlan,
    val raster: RasterParams?,
    val printer: PrinterJobSettings,
    /** Faces to print, grouped into jobs. Empty for pass-through, where the printer prints the PDF itself. */
    val passes: List<Pass>,
    val manualDuplex: ManualDuplex?,
    val decisions: List<Decision>,
) {
    /** True when the user has to act between two passes. */
    public val needsReload: Boolean get() = manualDuplex != null

    public val warnings: List<Decision>
        get() = decisions.filter { it.outcome == Decision.Outcome.IGNORED || it.outcome == Decision.Outcome.APPROXIMATED }
}

/** Why no plan could be made. */
public class PlanningException(message: String) : Exception(message)
