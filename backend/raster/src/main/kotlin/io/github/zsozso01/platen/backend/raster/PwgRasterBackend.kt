package io.github.zsozso01.platen.backend.raster

import io.github.zsozso01.platen.core.engine.Backend
import io.github.zsozso01.platen.core.engine.BackendJob
import io.github.zsozso01.platen.protocol.raster.PwgColorType
import io.github.zsozso01.platen.protocol.raster.PwgPageHeader
import io.github.zsozso01.platen.protocol.raster.PwgRasterWriter
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.Quality
import java.io.OutputStream

/**
 * Writes the planned faces as a PWG Raster (`image/pwg-raster`) document: every face is rendered by
 * the job's [io.github.zsozso01.platen.core.engine.SideRasterizer] in bands and streamed straight into
 * the encoder, so memory use is a few bands, never a whole page.
 */
public class PwgRasterBackend(private val bandRows: Int = DEFAULT_BAND_ROWS) : Backend {
    override val format: DocumentFormat = DocumentFormat.PWG_RASTER

    override fun write(job: BackendJob, out: OutputStream, progress: (JobEvent) -> Unit) {
        val plan = job.plan
        val raster = requireNotNull(plan.raster) { "PwgRasterBackend needs a raster plan" }
        val rasterizer = requireNotNull(job.rasterizer) { "PwgRasterBackend needs a rasterizer" }
        val type = requireNotNull(PwgColorType.fromKeyword(raster.pwgType)) { "Unsupported PWG type ${raster.pwgType}" }
        val sheet = plan.layout.sheet
        val width = sheet.widthPixels(raster.dpi)
        val height = sheet.heightPixels(raster.dpi)
        val bytesPerLine = type.bytesPerLine(width)
        require(raster.pixelFormat.bytesPerPixel * width == bytesPerLine) { "Pixel format ${raster.pixelFormat} does not match ${raster.pwgType}" }
        val quality = when (plan.printer.quality) {
            Quality.DRAFT -> 3
            Quality.NORMAL -> 4
            Quality.HIGH -> 5
            null -> 0
        }

        PwgRasterWriter(out).use { writer ->
            job.faces.forEachIndexed { index, face ->
                progress(JobEvent.Preparing(page = index + 1, totalPages = job.faces.size))
                writer.startPage(
                    PwgPageHeader(
                        widthPixels = width,
                        heightPixels = height,
                        resolutionDpiX = raster.dpi,
                        resolutionDpiY = raster.dpi,
                        colorType = type,
                        pageSizePoints = Math.round(sheet.widthPoints).toInt() to Math.round(sheet.heightPoints).toInt(),
                        pageSizeName = sheet.pwgName.orEmpty().take(63),
                        duplex = face.duplex,
                        tumble = face.tumble,
                        // The job's `copies` attribute carries the count; a second count in the page header would multiply it.
                        numCopies = 0,
                        printQuality = quality,
                        totalPageCount = job.faces.size,
                        crossFeedTransform = face.crossFeedTransform,
                        feedTransform = face.feedTransform,
                    ),
                )
                val line = ByteArray(bytesPerLine)
                if (face.side == null) {
                    line.fill(0xFF.toByte()) // blank face: white
                    repeat(height) { writer.writeLine(line) }
                } else {
                    rasterizer.rasterize(face.side!!, sheet, raster.dpi, raster.pixelFormat).use { side ->
                        check(side.widthPx == width && side.heightPx == height) { "Rasterizer returned ${side.widthPx}x${side.heightPx}, expected ${width}x$height" }
                        val band = ByteArray(bandRows * bytesPerLine)
                        var row = 0
                        while (row < height) {
                            val n = minOf(bandRows, height - row)
                            side.readRows(row, n, band)
                            for (r in 0 until n) {
                                System.arraycopy(band, r * bytesPerLine, line, 0, bytesPerLine)
                                writer.writeLine(line)
                            }
                            row += n
                        }
                    }
                }
                writer.endPage()
            }
        }
    }

    public companion object {
        /** 128 rows of an A4 page at 600 dpi in RGB is about 2 MB. */
        public const val DEFAULT_BAND_ROWS: Int = 128
    }
}
