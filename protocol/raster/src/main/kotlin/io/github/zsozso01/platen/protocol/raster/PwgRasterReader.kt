package io.github.zsozso01.platen.protocol.raster

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The input is not valid PWG Raster. */
public class PwgRasterException(message: String) : IOException(message)

/**
 * Reads PWG Raster back. Used by tests, by the fake printer (to turn received jobs into viewable
 * images) and for previews. Hardened: page dimensions are bounded and every length is checked, so a
 * hostile file cannot make it allocate more than one line.
 */
public class PwgRasterReader(private val input: InputStream) {
    private var syncChecked = false
    private var current: PwgPageHeader? = null
    private var rowsRead = 0
    private var repeatLeft = 0
    private var lastLine: ByteArray? = null

    /** Moves to the next page and returns its header, or null at the end of the file. */
    @Throws(IOException::class)
    public fun nextPage(): PwgPageHeader? {
        current?.let { skipRemainingRows(it) }
        if (!syncChecked) {
            val sync = readFully(4, allowEofAtStart = true) ?: return null
            if (!sync.contentEquals(PwgRasterWriter.SYNC_WORD)) throw PwgRasterException("missing RaS2 synchronisation word")
            syncChecked = true
        }
        val raw = readFully(PwgPageHeader.SIZE, allowEofAtStart = true) ?: return null
        val header = PwgPageHeader.decode(raw) { throw PwgRasterException(it) }
        current = header
        rowsRead = 0
        repeatLeft = 0
        lastLine = null
        return header
    }

    /** Reads the next uncompressed line of the current page, or null after the last line. */
    @Throws(IOException::class)
    public fun readLine(): ByteArray? {
        val h = checkNotNull(current) { "nextPage() was not called" }
        if (rowsRead >= h.heightPixels) return null
        if (repeatLeft == 0) {
            val repeat = readByte() + 1
            val line = decodeLine(h)
            lastLine = line
            repeatLeft = repeat
        }
        repeatLeft--
        rowsRead++
        return lastLine!!.copyOf()
    }

    private fun skipRemainingRows(h: PwgPageHeader) {
        while (rowsRead < h.heightPixels) readLine()
    }

    private fun decodeLine(h: PwgPageHeader): ByteArray {
        val unit = h.colorType.compressionUnitBytes
        val total = h.bytesPerLine
        val line = ByteArray(total)
        var pos = 0
        while (pos < total) {
            val control = readByte()
            if (control <= 127) {
                val bytes = (control + 1) * unit
                if (bytes > total - pos) throw PwgRasterException("run overruns the line")
                val pixel = readFully(unit)!!
                var written = 0
                while (written < bytes) {
                    System.arraycopy(pixel, 0, line, pos + written, unit)
                    written += unit
                }
                pos += bytes
            } else if (control == 128) {
                // "Fill the rest of the line with white" in the CUPS raster family; PWG writers never emit it.
                java.util.Arrays.fill(line, pos, total, 0xFF.toByte())
                pos = total
            } else {
                val count = 257 - control
                val bytes = count * unit
                if (bytes > total - pos) throw PwgRasterException("literal run overruns the line")
                val data = readFully(bytes)!!
                System.arraycopy(data, 0, line, pos, bytes)
                pos += bytes
            }
        }
        return line
    }

    private fun readByte(): Int {
        val b = input.read()
        if (b < 0) throw EOFException("unexpected end of PWG Raster data")
        return b
    }

    private fun readFully(n: Int, allowEofAtStart: Boolean = false): ByteArray? {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(bytes, read, n - read)
            if (r < 0) {
                if (read == 0 && allowEofAtStart) return null
                throw EOFException("unexpected end of PWG Raster data")
            }
            read += r
        }
        return bytes
    }
}
