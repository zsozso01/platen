package io.github.zsozso01.platen.protocol.raster

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream

/**
 * Streams a PWG Raster (`image/pwg-raster`, PWG 5102.4) document: sync word, then per page a header
 * and the PackBits-like compressed bitmap.
 *
 * Memory use is one line, however large the page. Usage:
 *
 * ```
 * PwgRasterWriter(out).use { w ->
 *     w.startPage(header)
 *     repeat(header.heightPixels) { w.writeLine(nextLine()) }
 *     w.endPage()
 * }
 * ```
 */
public class PwgRasterWriter(private val out: OutputStream) : Closeable {
    private var wroteSync = false
    private var header: PwgPageHeader? = null
    private var linesWritten = 0
    private var previousLine: ByteArray? = null
    private var repeatCount = 0
    private val encoded = ByteArrayBuilder()

    @Throws(IOException::class)
    public fun startPage(header: PwgPageHeader) {
        check(this.header == null) { "endPage() was not called for the previous page" }
        if (!wroteSync) {
            out.write(SYNC_WORD)
            wroteSync = true
        }
        out.write(header.encode())
        this.header = header
        linesWritten = 0
        previousLine = null
        repeatCount = 0
    }

    /** Writes the next line, exactly `header.bytesPerLine` bytes. Identical consecutive lines are collapsed. */
    @Throws(IOException::class)
    public fun writeLine(line: ByteArray) {
        val h = checkNotNull(header) { "startPage() was not called" }
        require(line.size == h.bytesPerLine) { "Line is ${line.size} bytes, expected ${h.bytesPerLine}" }
        check(linesWritten < h.heightPixels) { "More lines than the declared height ${h.heightPixels}" }
        linesWritten++
        val previous = previousLine
        if (previous != null && repeatCount < MAX_LINE_REPEAT && previous.contentEquals(line)) {
            repeatCount++
            return
        }
        flushLine()
        previousLine = line.copyOf()
        repeatCount = 1
    }

    @Throws(IOException::class)
    public fun endPage() {
        val h = checkNotNull(header) { "startPage() was not called" }
        check(linesWritten == h.heightPixels) { "Page has $linesWritten lines but the header says ${h.heightPixels}" }
        flushLine()
        header = null
    }

    @Throws(IOException::class)
    override fun close() {
        check(header == null) { "Closed in the middle of a page" }
        out.flush()
    }

    private fun flushLine() {
        val h = header ?: return
        val line = previousLine ?: return
        encoded.reset()
        encoded.write(repeatCount - 1)
        encodeLine(line, h.colorType.compressionUnitBytes, encoded)
        out.write(encoded.buffer, 0, encoded.size)
        previousLine = null
        repeatCount = 0
    }

    public companion object {
        /** `RaS2`, the PWG Raster synchronisation word. */
        public val SYNC_WORD: ByteArray = byteArrayOf(0x52, 0x61, 0x53, 0x32)

        private const val MAX_LINE_REPEAT = 256
        private const val MAX_RUN = 128

        /**
         * PackBits-like encoding of one line (PWG 5102.4 section 4.4): runs of 1 to 128 equal units are
         * `count-1` followed by the unit; sequences of 2 to 128 different units are `257-count` followed
         * by the units. A unit is a whole pixel, or one packed byte for 1-bit images.
         */
        internal fun encodeLine(line: ByteArray, unit: Int, out: ByteArrayBuilder) {
            val units = line.size / unit
            // For one-byte units, a run of two equal bytes costs as much as two literal bytes, so only
            // break a literal sequence for runs of three or more.
            val minRun = if (unit == 1) 3 else 2

            fun same(a: Int, b: Int): Boolean {
                val ao = a * unit
                val bo = b * unit
                for (k in 0 until unit) if (line[ao + k] != line[bo + k]) return false
                return true
            }

            fun runAt(i: Int): Int {
                var n = 1
                while (i + n < units && n < MAX_RUN && same(i, i + n)) n++
                return n
            }

            var i = 0
            while (i < units) {
                val run = runAt(i)
                if (run >= minRun) {
                    out.write(run - 1)
                    out.write(line, i * unit, unit)
                    i += run
                } else {
                    var count = 0
                    val start = i
                    while (i < units && count < MAX_RUN && runAt(i) < minRun) {
                        count++
                        i++
                    }
                    if (count == 1) {
                        out.write(0) // a single unit is a run of one
                    } else {
                        out.write(257 - count)
                    }
                    out.write(line, start * unit, count * unit)
                }
            }
        }
    }
}

/** Minimal growable byte buffer so the writer avoids per-line allocation churn. */
internal class ByteArrayBuilder(initial: Int = 4096) {
    var buffer = ByteArray(initial)
        private set
    var size = 0
        private set

    fun reset() {
        size = 0
    }

    fun write(b: Int) {
        ensure(1)
        buffer[size++] = b.toByte()
    }

    fun write(src: ByteArray, offset: Int, length: Int) {
        ensure(length)
        System.arraycopy(src, offset, buffer, size, length)
        size += length
    }

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
    }
}
