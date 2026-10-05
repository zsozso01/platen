package io.github.zsozso01.platen.backend.pdf

import io.github.zsozso01.platen.core.engine.Backend
import io.github.zsozso01.platen.core.engine.BackendJob
import io.github.zsozso01.platen.core.engine.RasterPixelFormat
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes the planned faces as a PDF in which every face is one full-sheet image. It lets a printer that
 * only takes PDF, PostScript or PCL (no raster formats) get every layout feature Platen has: page ranges,
 * reverse order, several pages per sheet, booklets, custom margins and scaling, manual duplex, and images
 * as the source. The cost is that text becomes pixels, which is why a PDF that can be sent unchanged is.
 *
 * Faces are rendered in bands and streamed straight into the compressor, so memory use is a few bands, never
 * a page. Image streams use an indirect `/Length` (written after the data) for the same reason; that is
 * ordinary PDF and what pdfTeX and Cairo emit.
 */
public class PdfRasterBackend(private val bandRows: Int = DEFAULT_BAND_ROWS) : Backend {
    override val format: DocumentFormat = DocumentFormat.PDF

    override fun write(job: BackendJob, out: OutputStream, progress: (JobEvent) -> Unit) {
        val raster = requireNotNull(job.plan.raster) { "PdfRasterBackend needs a raster plan" }
        val rasterizer = requireNotNull(job.rasterizer) { "PdfRasterBackend needs a rasterizer" }
        val sheet = job.plan.layout.sheet
        val width = sheet.widthPixels(raster.dpi)
        val height = sheet.heightPixels(raster.dpi)
        val bytesPerLine = width * raster.pixelFormat.bytesPerPixel
        val widthPoints = number(sheet.widthPoints)
        val heightPoints = number(sheet.heightPoints)
        val points = "$widthPoints $heightPoints"
        val colorSpace = if (raster.pixelFormat == RasterPixelFormat.GRAY8) "/DeviceGray" else "/DeviceRGB"

        val pdf = PdfWriter(out)
        pdf.header()
        val pageObjects = ArrayList<Int>(job.faces.size)
        job.faces.forEachIndexed { index, face ->
            progress(JobEvent.Preparing(page = index + 1, totalPages = job.faces.size))
            val page = pdf.reserve()
            val contents = pdf.reserve()
            pageObjects += page
            val side = face.side
            if (side == null) {
                // A blank face (the padding of a duplex job) is a page with nothing on it.
                pdf.obj(page) { "<< /Type /Page /Parent $PAGES_OBJECT 0 R /MediaBox [0 0 $points] /Resources << >> /Contents $contents 0 R >>" }
                pdf.stream(contents, "", "q Q\n".toByteArray(Charsets.US_ASCII))
                return@forEachIndexed
            }
            val image = pdf.reserve()
            val length = pdf.reserve()
            pdf.obj(page) {
                "<< /Type /Page /Parent $PAGES_OBJECT 0 R /MediaBox [0 0 $points] " +
                    "/Resources << /XObject << /Im0 $image 0 R >> /ProcSet [/PDF /ImageB /ImageC] >> /Contents $contents 0 R >>"
            }
            pdf.stream(contents, "", "q $widthPoints 0 0 $heightPoints 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII))
            pdf.streamOf(
                image,
                "/Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace $colorSpace /BitsPerComponent 8 /Filter /FlateDecode",
                length,
            ) { sink ->
                rasterizer.rasterize(side, sheet, raster.dpi, raster.pixelFormat).use { rendered ->
                    check(rendered.widthPx == width && rendered.heightPx == height) {
                        "Rasterizer returned ${rendered.widthPx}x${rendered.heightPx}, expected ${width}x$height"
                    }
                    val band = ByteArray(bandRows * bytesPerLine)
                    var row = 0
                    while (row < height) {
                        val n = minOf(bandRows, height - row)
                        rendered.readRows(row, n, band)
                        sink.write(band, 0, n * bytesPerLine)
                        row += n
                    }
                }
            }
        }
        pdf.obj(PAGES_OBJECT) { "<< /Type /Pages /Count ${pageObjects.size} /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] >>" }
        pdf.obj(CATALOG_OBJECT) { "<< /Type /Catalog /Pages $PAGES_OBJECT 0 R >>" }
        pdf.finish(root = CATALOG_OBJECT)
    }

    public companion object {
        public const val DEFAULT_BAND_ROWS: Int = 128

        private const val CATALOG_OBJECT = 1
        private const val PAGES_OBJECT = 2

        private fun number(value: Double): String = "%.2f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }
}

/** The few PDF syntax rules this backend needs: numbered objects, streams, a cross-reference table. */
internal class PdfWriter(out: OutputStream) {
    private val counter = CountingOutputStream(out)
    private val offsets = HashMap<Int, Long>()
    private var next = 3 // 1 and 2 are the catalog and the page tree, written last

    fun header() {
        counter.write("%PDF-1.4\n".toByteArray(Charsets.US_ASCII))
        // Four bytes above 127 tell tools that the file is binary.
        counter.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    fun reserve(): Int = next++

    fun obj(number: Int, body: () -> String) {
        offsets[number] = counter.count
        counter.write("$number 0 obj\n${body()}\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    /** A stream whose bytes are already in memory, with a direct length. */
    fun stream(number: Int, dictionary: String, data: ByteArray) {
        offsets[number] = counter.count
        counter.write("$number 0 obj\n<< $dictionary /Length ${data.size} >>\nstream\n".toByteArray(Charsets.US_ASCII))
        counter.write(data)
        counter.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
    }

    /**
     * A Flate-compressed stream produced by [fill], with its length written afterwards as the separate
     * object [lengthObject], so the data never has to be held in memory.
     */
    fun streamOf(number: Int, dictionary: String, lengthObject: Int, fill: (OutputStream) -> Unit) {
        offsets[number] = counter.count
        counter.write("$number 0 obj\n<< $dictionary /Length $lengthObject 0 R >>\nstream\n".toByteArray(Charsets.US_ASCII))
        val start = counter.count
        val deflater = Deflater(COMPRESSION_LEVEL)
        try {
            val compressed = DeflaterOutputStream(counter, deflater, 32 * 1024)
            fill(compressed)
            compressed.finish()
        } finally {
            deflater.end()
        }
        val length = counter.count - start
        counter.write("\nendstream\nendobj\n".toByteArray(Charsets.US_ASCII))
        obj(lengthObject) { length.toString() }
    }

    fun finish(root: Int) {
        val xref = counter.count
        val size = (offsets.keys.max()) + 1
        val table = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            val offset = checkNotNull(offsets[n]) { "PDF object $n was never written" }
            table.append("%010d 00000 n \n".format(Locale.ROOT, offset))
        }
        table.append("trailer\n<< /Size $size /Root $root 0 R >>\nstartxref\n$xref\n%%EOF\n")
        counter.write(table.toString().toByteArray(Charsets.US_ASCII))
        counter.flush()
    }

    private companion object {
        /** Fast: pages are mostly white, which compresses well at any level, and a phone should not spend seconds on it. */
        const val COMPRESSION_LEVEL = 3
    }
}

internal class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
    var count: Long = 0
        private set

    override fun write(b: Int) {
        inner.write(b)
        count++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        inner.write(b, off, len)
        count += len
    }

    override fun flush() = inner.flush()
}
