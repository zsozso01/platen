package io.github.zsozso01.platen.core.engine

import io.github.zsozso01.platen.core.layout.PageGeometry
import io.github.zsozso01.platen.core.layout.Side
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.MediaSize
import io.github.zsozso01.platen.core.model.PrinterCapabilities
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** A document to print. Implemented on Android over a content Uri; in tests by synthetic pages. */
public interface DocumentSource {
    public val name: String
    public val pageCount: Int

    /** Size of page [pageIndex] (0-based) in points, as it is displayed (including any rotation). */
    public fun pageGeometry(pageIndex: Int): PageGeometry

    /** The original PDF file if this document *is* a PDF that may be sent to the printer unchanged. */
    public val pdf: PdfFile?
}

public interface PdfFile {
    public val length: Long

    public fun open(): InputStream
}

/** Pixel layouts the rasteriser can produce. */
public enum class RasterPixelFormat(public val bytesPerPixel: Int) {
    GRAY8(1),
    RGB24(3),
}

/** One printed side rendered as pixels, readable top to bottom in bands so memory stays bounded. */
public interface RasterSide : Closeable {
    public val widthPx: Int
    public val heightPx: Int
    public val format: RasterPixelFormat

    /** Fills [into] with [count] rows starting at [startRow], each exactly `widthPx * format.bytesPerPixel` bytes. */
    public fun readRows(startRow: Int, count: Int, into: ByteArray)
}

/**
 * Turns a laid-out side into pixels. The Android implementation renders the document's pages with
 * `PdfRenderer` through each placement's transform and clip; tests use a synthetic one.
 */
public interface SideRasterizer : Closeable {
    public fun rasterize(side: Side, sheet: MediaSize, dpi: Int, format: RasterPixelFormat): RasterSide
}

/** A page-description-language writer: turns the planned job into bytes in one [format]. */
public interface Backend {
    public val format: DocumentFormat

    /** Writes the complete document to [out]. Reports progress as [JobEvent.Preparing]. */
    public fun write(job: BackendJob, out: OutputStream, progress: (JobEvent) -> Unit)
}

public class BackendJob(
    public val plan: PrintPlan,
    public val document: DocumentSource,
    public val rasterizer: SideRasterizer?,
)

/** A cooperative cancellation handle shared by the engine and a blocking job protocol. */
public class CancelToken {
    @Volatile
    public var isCancelled: Boolean = false
        private set

    private val actions = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Registers something to run when cancelled (for example closing a socket). Runs now if already cancelled. */
    public fun onCancel(action: () -> Unit) {
        actions += action
        if (isCancelled) runCatching(action)
    }

    public fun cancel() {
        if (isCancelled) return
        isCancelled = true
        actions.forEach { runCatching(it) }
    }
}

/** The result of asking a printer what it is and what it can do. */
public class PrinterProbe(
    public val capabilities: PrinterCapabilities,
    public val state: io.github.zsozso01.platen.core.model.PrinterState,
    public val issues: List<io.github.zsozso01.platen.core.model.PrinterIssue>,
    /** The printer's own name and model string, when it reports one. */
    public val makeAndModel: String?,
)

/** What a job protocol needs to send one job. */
public class JobSubmission(
    public val jobName: String,
    public val format: DocumentFormat,
    /** The finished document, spooled to disk so its length is known and a retry needs no re-render. */
    public val payload: File,
    public val settings: PrinterJobSettings,
    public val raster: RasterParams?,
)

/**
 * Describes and controls a job on a printer (IPP, PJL, ...). Blocking: the engine runs it on an IO
 * dispatcher and cancels through [CancelToken].
 */
public interface JobProtocol {
    public val id: String

    @Throws(java.io.IOException::class)
    public fun probe(): PrinterProbe

    /**
     * Sends the job and follows it until it reaches a terminal state, reporting [JobEvent.Sending],
     * [JobEvent.Accepted], [JobEvent.Printing] and finally [JobEvent.Completed], [JobEvent.Canceled]
     * or [JobEvent.Failed]. Never throws for printer-side failures; those are reported as events.
     */
    public fun submit(submission: JobSubmission, cancel: CancelToken, listener: (JobEvent) -> Unit)
}
