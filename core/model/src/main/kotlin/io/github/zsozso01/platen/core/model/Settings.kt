package io.github.zsozso01.platen.core.model

public enum class Sides {
    ONE_SIDED,
    TWO_SIDED_LONG_EDGE,
    TWO_SIDED_SHORT_EDGE,
    ;

    public val isDuplex: Boolean get() = this != ONE_SIDED
}

public enum class ColorMode { AUTO, COLOR, MONOCHROME }

public enum class Quality { DRAFT, NORMAL, HIGH }

/** How a page's contents are turned onto the paper. */
public enum class Orientation {
    /** Pick portrait or landscape per document so the content fits the paper best. */
    AUTO,
    PORTRAIT,
    LANDSCAPE,
}

/** How content is fitted to the printable area. Matches the IPP `print-scaling` vocabulary. */
public sealed interface Scaling {
    /** Shrink only if the content is too big for the paper; never enlarge. The sensible default. */
    public data object ShrinkToFit : Scaling

    /** Scale up or down so the whole page fits the printable area. */
    public data object FitToPage : Scaling

    /** Scale so the printable area is completely covered; the overflow is cropped. */
    public data object Fill : Scaling

    /** 100%: print at the size the document asks for. */
    public data object ActualSize : Scaling

    /** A fixed zoom, in percent (1 to 1000). */
    public data class Custom(val percent: Int) : Scaling {
        init {
            require(percent in 1..1000) { "Scale must be between 1% and 1000%" }
        }
    }
}

/** Which pages of the document to print. Page numbers are 1-based. */
public data class PageSelection(
    /** Inclusive ranges in the order given, or null for every page. */
    val ranges: List<IntRange>? = null,
    val parity: Parity = Parity.ALL,
) {
    init {
        ranges?.forEach { require(it.first >= 1 && it.last >= it.first) { "Bad page range $it" } }
    }

    public enum class Parity { ALL, ODD_ONLY, EVEN_ONLY }

    /**
     * Resolves to concrete 1-based page numbers for a document of [pageCount] pages, in print order
     * (before any reversal). Ranges beyond the document are clipped; a page named twice prints twice.
     */
    public fun resolve(pageCount: Int): List<Int> {
        val base = ranges?.flatMap { r -> (r.first..minOf(r.last, pageCount)).toList() } ?: (1..pageCount).toList()
        return when (parity) {
            Parity.ALL -> base
            Parity.ODD_ONLY -> base.filter { it % 2 == 1 }
            Parity.EVEN_ONLY -> base.filter { it % 2 == 0 }
        }
    }

    public companion object {
        public val ALL: PageSelection = PageSelection()

        /** Parses text like `1-3, 5, 8-` (an open end means "to the last page"). Returns null if it is malformed. */
        public fun parse(text: String, pageCount: Int): PageSelection? {
            val parts = text.split(',').map(String::trim).filter(String::isNotEmpty)
            if (parts.isEmpty()) return ALL
            val ranges = mutableListOf<IntRange>()
            for (part in parts) {
                val dash = part.indexOf('-')
                val range = if (dash < 0) {
                    val n = part.toIntOrNull() ?: return null
                    n..n
                } else {
                    val fromText = part.substring(0, dash).trim()
                    val toText = part.substring(dash + 1).trim()
                    if (fromText.isEmpty() && toText.isEmpty()) return null // a lone "-" is ambiguous
                    val from = fromText.ifEmpty { "1" }.toIntOrNull() ?: return null
                    val to = toText.ifEmpty { pageCount.toString() }.toIntOrNull() ?: return null
                    from..to
                }
                if (range.first < 1 || range.last < range.first) return null
                ranges += range
            }
            return PageSelection(ranges)
        }
    }
}

/** How much empty border to leave around the printed content. */
public sealed interface MarginSetting {
    /** Respect only the border the printer physically cannot print on. */
    public data object PrinterDefault : MarginSetting

    /** Use the whole sheet; the printer clips whatever lies in its unprintable border. */
    public data object None : MarginSetting

    /** At least these margins (never less than the printer's own unprintable border). */
    public data class Custom(val margins: Margins) : MarginSetting
}

/** Which edge a booklet is bound on. */
public enum class BookletBinding { LEFT, RIGHT }

/** Several document pages on one sheet. */
public data class PagesPerSheet(
    val count: Int = 1,
    val order: Order = Order.LEFT_TO_RIGHT_THEN_DOWN,
    val border: Boolean = false,
) {
    init {
        require(count in SUPPORTED) { "Pages per sheet must be one of $SUPPORTED" }
    }

    public enum class Order { LEFT_TO_RIGHT_THEN_DOWN, TOP_TO_BOTTOM_THEN_RIGHT, RIGHT_TO_LEFT_THEN_DOWN, BOTTOM_TO_TOP_THEN_LEFT }

    public companion object {
        public val SUPPORTED: Set<Int> = setOf(1, 2, 4, 6, 9, 16)
        public val ONE: PagesPerSheet = PagesPerSheet()
    }
}

/** What the user asked for, independent of any printer. The planner turns this into a [PrintPlan]-style decision per printer. */
public data class PrintSettings(
    val copies: Int = 1,
    val collate: Boolean = true,
    val pages: PageSelection = PageSelection.ALL,
    val reverseOrder: Boolean = false,
    val orientation: Orientation = Orientation.AUTO,
    /** Null means the printer's default paper. */
    val paper: MediaSize? = null,
    val paperType: MediaType? = null,
    val tray: MediaSource? = null,
    val scaling: Scaling = Scaling.ShrinkToFit,
    val sides: Sides = Sides.ONE_SIDED,
    val color: ColorMode = ColorMode.AUTO,
    val quality: Quality = Quality.NORMAL,
    /** Square resolution in dpi, or null to let quality decide. */
    val resolutionDpi: Int? = null,
    /** Save toner/ink. Honoured only where the printer or its language has such a mode. */
    val economy: Boolean = false,
    val pagesPerSheet: PagesPerSheet = PagesPerSheet.ONE,
    val margins: MarginSetting = MarginSetting.PrinterDefault,
    /** Print as a folded booklet (implies imposing two pages per side and duplex). */
    val booklet: Boolean = false,
    val bookletBinding: BookletBinding = BookletBinding.LEFT,
    val outputBin: String? = null,
) {
    init {
        require(copies in 1..999) { "Copies must be between 1 and 999" }
        resolutionDpi?.let { require(it in 72..2400) { "Resolution must be between 72 and 2400 dpi" } }
    }
}
