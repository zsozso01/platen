package io.github.zsozso01.platen.protocol.ipp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional byte pipe to a printer's IPP endpoint. Implemented over TCP and TLS sockets by the
 * network transport and over a USB IPP-over-USB interface by the USB transport; IPP itself is HTTP
 * and does not care which.
 *
 * Reads and writes block; the owner sets timeouts on the underlying channel and cancels by calling
 * [close] from another thread.
 */
public interface IppConnection : Closeable {
    public val input: InputStream
    public val output: OutputStream

    /**
     * Called once a complete HTTP response has been read, before any HTTP error is raised. A carrier that
     * cannot be closed like a socket (IPP-over-USB) uses this to know the pipe holds no stale bytes and can
     * go back into use; if [close] arrives without it, the exchange was cut short and the pipe needs recovery.
     */
    public fun responseCompleted() {}
}

/** Opens a fresh [IppConnection]. Called once per request: Platen never reuses an HTTP connection. */
public fun interface IppConnector {
    @Throws(IOException::class)
    public fun connect(): IppConnection
}

/** A document (or any large body) streamed after the IPP header. */
public class IppDocument(
    public val stream: InputStream,
    /** Exact size if known. Known sizes are sent with `Content-Length`; unknown ones are chunked. */
    public val length: Long? = null,
)

public class HttpResponse(
    public val status: Int,
    public val reason: String,
    /** Header names lower-cased. */
    public val headers: Map<String, String>,
    public val body: ByteArray,
)

/** The printer answered with an HTTP error before or instead of an IPP response. */
public class IppHttpException(public val status: Int, public val reason: String, public val response: HttpResponse) :
    IOException("HTTP $status $reason") {
    /** True when the printer demands credentials (401). */
    public val needsAuthentication: Boolean get() = status == 401

    /** True when the printer demands an encrypted connection (426 Upgrade Required). */
    public val needsTls: Boolean get() = status == 426
}

/** HTTP/1.1 POST of `application/ipp` over an [IppConnector]. */
public class IppHttpTransport(
    private val connector: IppConnector,
    /** Value of the `Host` header: `host` or `host:port`. */
    private val host: String,
    /** Request path, e.g. `/ipp/print`. */
    public val path: String,
    private val userAgent: String = "Platen",
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    /**
     * Send `Connection: close`. Right for TCP. Wrong for IPP-over-USB, where the USB interface *is*
     * the connection and stays open: there, set false and rely on `Content-Length`/chunked framing
     * (the response must be read to its end, or stale bytes poison the next request).
     */
    private val sendConnectionClose: Boolean = true,
    /**
     * Allow a response with neither `Content-Length` nor chunking, read until the peer closes. Right
     * for TCP. Wrong for IPP-over-USB, where nothing ever closes: there it would hang forever, so a
     * response without framing is reported as an error instead.
     */
    private val allowReadToEnd: Boolean = true,
) {
    /**
     * Sends [ipp] followed by [document] and returns the response. [onBytesSent] receives the running
     * total of document bytes written. Throws [IppHttpException] for non-200 answers.
     */
    @Throws(IOException::class)
    public fun post(
        ipp: ByteArray,
        document: IppDocument? = null,
        onBytesSent: (Long) -> Unit = {},
    ): HttpResponse {
        connector.connect().use { connection ->
            val input = BufferedInputStream(connection.input, 8 * 1024)
            var writeFailure: IOException? = null
            try {
                writeRequest(connection.output, ipp, document, onBytesSent)
            } catch (e: IOException) {
                // A printer that rejects the request early (401, 426, ...) may close the connection
                // while we are still sending. Its answer is usually already in our receive buffer.
                writeFailure = e
            }
            val response = try {
                HttpWire.readResponse(input, maxResponseBytes, allowReadToEnd)
            } catch (e: IOException) {
                throw writeFailure ?: e
            }
            connection.responseCompleted()
            if (response.status != 200) throw IppHttpException(response.status, response.reason, response)
            return response
        }
    }

    private fun writeRequest(out: OutputStream, ipp: ByteArray, document: IppDocument?, onBytesSent: (Long) -> Unit) {
        val knownLength = if (document == null) 0L else document.length
        val total = knownLength?.plus(ipp.size)
        val head = buildString {
            append("POST ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: ").append(userAgent).append("\r\n")
            append("Content-Type: application/ipp\r\n")
            append("Accept: application/ipp\r\n")
            append("Accept-Encoding: identity\r\n")
            if (sendConnectionClose) append("Connection: close\r\n")
            for ((k, v) in extraHeaders) append(k).append(": ").append(v).append("\r\n")
            if (total != null) append("Content-Length: ").append(total).append("\r\n") else append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        val chunked = total == null
        if (chunked) HttpWire.writeChunk(out, ipp, 0, ipp.size) else out.write(ipp)
        if (document != null) {
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var sent = 0L
            while (true) {
                val n = document.stream.read(buffer)
                if (n < 0) break
                if (chunked) HttpWire.writeChunk(out, buffer, 0, n) else out.write(buffer, 0, n)
                sent += n
                onBytesSent(sent)
            }
            if (!chunked && sent != document.length) throw IOException("Document was $sent bytes but declared ${document.length}")
        }
        if (chunked) out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    public companion object {
        public const val DEFAULT_MAX_RESPONSE_BYTES: Int = 8 * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 16 * 1024
    }
}

internal object HttpWire {
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024

    fun writeChunk(out: OutputStream, data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        out.write((Integer.toHexString(length) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(data, offset, length)
        out.write(CRLF)
    }

    /** Reads one response, skipping any `1xx` interim responses. */
    fun readResponse(input: InputStream, maxBody: Int, allowReadToEnd: Boolean = true): HttpResponse {
        while (true) {
            val statusLine = readLine(input) ?: throw IOException("Connection closed before an HTTP response")
            val parts = statusLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].startsWith("HTTP/")) throw IOException("Not an HTTP response: '${statusLine.take(60)}'")
            val status = parts[1].toIntOrNull() ?: throw IOException("Bad HTTP status line: '${statusLine.take(60)}'")
            val reason = parts.getOrElse(2) { "" }
            val headers = readHeaders(input)
            if (status in 100..199) continue
            val body = readBody(input, headers, status, maxBody, allowReadToEnd)
            return HttpResponse(status, reason, headers, body)
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: throw IOException("Connection closed inside HTTP headers")
            if (line.isEmpty()) return headers
            total += line.length
            if (total > MAX_HEADER_BYTES) throw IOException("HTTP headers too large")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>, status: Int, maxBody: Int, allowReadToEnd: Boolean): ByteArray {
        if (status == 204 || status == 304) return ByteArray(0)
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        if (transferEncoding != null && "chunked" in transferEncoding) return readChunked(input, maxBody)
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        if (length != null) {
            if (length < 0 || length > maxBody) throw IOException("HTTP body of $length bytes exceeds the $maxBody byte limit")
            return input.readNBytesStrict(length.toInt())
        }
        if (!allowReadToEnd) throw IOException("HTTP response has no Content-Length and is not chunked; refusing to wait for a close that will never come")
        return readToEnd(input, maxBody)
    }

    private fun readChunked(input: InputStream, maxBody: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("Connection closed inside a chunked body")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk size '${sizeLine.take(20)}'")
            if (size < 0) throw IOException("Negative chunk size")
            if (size == 0) {
                // Optional trailers, ended by an empty line.
                while (true) {
                    val trailer = readLine(input) ?: return out.toByteArray()
                    if (trailer.isEmpty()) return out.toByteArray()
                }
            }
            if (out.size().toLong() + size > maxBody) throw IOException("Chunked HTTP body exceeds the $maxBody byte limit")
            out.write(input.readNBytesStrict(size))
            val crlf = readLine(input)
            if (crlf == null || crlf.isNotEmpty()) throw IOException("Chunk not terminated by CRLF")
        }
    }

    private fun readToEnd(input: InputStream, maxBody: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > maxBody) throw IOException("HTTP body exceeds the $maxBody byte limit")
            out.write(buffer, 0, n)
        }
    }

    /** Reads up to CRLF (or LF). Returns null at end of stream before any byte. */
    fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (sb.length >= MAX_LINE_BYTES) throw IOException("HTTP line too long")
            sb.append(b.toChar())
        }
    }

    private fun InputStream.readNBytesStrict(n: Int): ByteArray {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(bytes, read, n - read)
            if (r < 0) throw IOException("Connection closed after $read of $n body bytes")
            read += r
        }
        return bytes
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
}
