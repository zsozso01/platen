package io.github.zsozso01.platen.testing.fakeprinter

import io.github.zsozso01.platen.protocol.ipp.GroupTag
import io.github.zsozso01.platen.protocol.ipp.IppAttribute
import io.github.zsozso01.platen.protocol.ipp.IppConnection
import io.github.zsozso01.platen.protocol.ipp.IppConnector
import io.github.zsozso01.platen.protocol.ipp.IppDecoder
import io.github.zsozso01.platen.protocol.ipp.IppEncoder
import io.github.zsozso01.platen.protocol.ipp.IppMessage
import io.github.zsozso01.platen.protocol.ipp.IppOperation
import io.github.zsozso01.platen.protocol.ipp.IppParseException
import io.github.zsozso01.platen.protocol.ipp.IppStatus
import io.github.zsozso01.platen.protocol.ipp.IppString
import io.github.zsozso01.platen.protocol.ipp.ippMessage
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** A job the fake printer accepted. */
class ReceivedJob(
    val id: Int,
    val jobName: String?,
    val documentFormat: String?,
    val jobAttributes: List<IppAttribute>,
    val document: ByteArray,
)

/** Knobs for provoking failures. All are re-read on every request, so tests can flip them mid-test. */
class FakeBehavior {
    /** Reply to every request with this HTTP status and no body (e.g. 401, 426, 503). */
    @Volatile var httpStatus: Int? = null

    /** Reply to the next IPP request with this IPP status code, then reset to null. */
    @Volatile var nextIppStatus: Int? = null

    /** Pause before answering. */
    @Volatile var delayMillis: Long = 0

    /** Close the socket after reading this many body bytes of a request (simulates a dropped connection). */
    @Volatile var dropAfterBodyBytes: Int? = null

    /** If set, requests whose `Host` header (without port) differs get `400 Bad Request`, as some USB printers do. */
    @Volatile var requiredHost: String? = null

    /** Number of `Get-Job-Attributes` polls before a job reports `completed` (it is `processing` before). */
    @Volatile var pollsUntilCompleted: Int = 2

    /** If a job carries this attribute, reject it with `client-error-attributes-or-values-not-supported` naming it. */
    @Volatile var rejectAttribute: String? = null

    /** What `printer-state` and `printer-state-reasons` report (3 idle, 4 processing, 5 stopped). */
    @Volatile var printerState: Int = 3
    @Volatile var printerStateReasons: List<String> = listOf("none")

    /** Report jobs as `processing-stopped` (for example out of paper) instead of progressing. */
    @Volatile var jobStopped: Boolean = false
}

/**
 * An in-process IPP printer over a real TCP socket: HTTP/1.1 framing (Content-Length, chunked,
 * `Expect: 100-continue`), IPP decoding and the handful of operations a print client needs.
 * Used by unit tests and, via [main], as a stand-in printer for the emulator.
 */
class FakeIppPrinter(
    val profile: FakePrinterProfile,
    port: Int = 0,
    bindAddress: InetAddress = InetAddress.getLoopbackAddress(),
    val path: String = "/ipp/print",
) : Closeable {
    private val server = ServerSocket(port, 16, bindAddress)
    val port: Int get() = server.localPort
    val behavior = FakeBehavior()
    val uri: String get() = "ipp://${server.inetAddress.hostAddress}:$port$path"

    /** Every decoded request, in arrival order. */
    val requests: MutableList<IppMessage> = CopyOnWriteArrayList()
    val jobs: MutableList<ReceivedJob> = CopyOnWriteArrayList()

    /** HTTP headers (names lower-cased) of every request that reached the IPP layer. */
    val requestHeaders: MutableList<Map<String, String>> = CopyOnWriteArrayList()

    private val nextJobId = AtomicInteger(1)
    private val polls = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
    private val canceled = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    @Volatile private var running = true
    private val acceptThread = thread(name = "fake-ipp-printer-accept", isDaemon = true) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: java.io.IOException) {
                break
            }
            thread(name = "fake-ipp-printer-conn", isDaemon = true) { handle(socket) }
        }
    }

    /** An [IppConnector] that dials this printer. */
    fun connector(): IppConnector = IppConnector {
        val socket = Socket(server.inetAddress, port).apply { soTimeout = 15_000 }
        object : IppConnection {
            override val input: InputStream = socket.getInputStream()
            override val output = socket.getOutputStream()
            override fun close() = socket.close()
        }
    }

    override fun close() {
        running = false
        server.close()
        acceptThread.join(2_000)
    }

    // --- HTTP ---------------------------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.use {
            socket.soTimeout = 15_000
            try {
                serve(socket.getInputStream(), socket.getOutputStream(), keepAlive = false)
            } catch (_: java.io.IOException) {
                // Client went away; nothing to do.
            }
        }
    }

    /**
     * Serves HTTP/IPP on any pair of streams: a socket, or a simulated USB pipe. With [keepAlive] it answers
     * request after request until the input ends (a USB interface is one long connection); otherwise it
     * answers one request and sends `Connection: close`.
     */
    fun serve(rawInput: InputStream, out: java.io.OutputStream, keepAlive: Boolean) {
        val input = rawInput.buffered()
        try {
            do {
                if (!exchange(input, out, keepAlive)) return
            } while (keepAlive)
        } catch (_: java.io.IOException) {
            // The other side went away.
        }
    }

    /** Handles one request. Returns false when there was nothing to read (the stream ended). */
    private fun exchange(input: InputStream, out: java.io.OutputStream, keepAlive: Boolean): Boolean {
        val requestLine = readLine(input) ?: return false
        if (!requestLine.startsWith("POST ")) {
            respondHttp(out, 405, "Method Not Allowed", keepAlive)
            return true
        }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        requestHeaders += headers.toMap()
        val requestedPath = requestLine.split(' ').getOrNull(1)
        if (requestedPath != path) {
            readBody(input, headers)
            respondHttp(out, 404, "Not Found", keepAlive)
            return true
        }
        behavior.requiredHost?.let { required ->
            if (headers["host"]?.substringBefore(':') != required) {
                readBody(input, headers)
                respondHttp(out, 400, "Bad Request", keepAlive)
                return true
            }
        }
        if (behavior.delayMillis > 0) Thread.sleep(behavior.delayMillis)
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        behavior.httpStatus?.let {
            readBody(input, headers)
            respondHttp(out, it, "Injected", keepAlive)
            return true
        }
        val body = readBody(input, headers) ?: return false
        val ippResponse = try {
            val decoded = IppDecoder.decode(body)
            requests += decoded.message
            dispatch(decoded.message, body.copyOfRange(decoded.dataOffset, body.size))
        } catch (e: IppParseException) {
            status(IppStatus.CLIENT_ERROR_BAD_REQUEST, 1, "bad request: ${e.message}")
        }
        val bytes = IppEncoder.encode(ippResponse)
        val connection = if (keepAlive) "" else "Connection: close\r\n"
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${bytes.size}\r\n$connection\r\n".toByteArray())
        out.write(bytes)
        out.flush()
        return true
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray? {
        val out = ByteArrayOutputStream()
        val limit = behavior.dropAfterBodyBytes
        fun copy(n: Int): Boolean {
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size, left))
                if (r < 0) return false
                out.write(buf, 0, r)
                left -= r
                if (limit != null && out.size() >= limit) return false
            }
            return true
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: return null
                if (size == 0) {
                    while (readLine(input)?.isNotEmpty() == true) { /* trailers */ }
                    break
                }
                if (!copy(size)) return null
                readLine(input)
            }
        } else {
            val length = headers["content-length"]?.toIntOrNull() ?: return null
            if (!copy(length)) return null
        }
        return out.toByteArray()
    }

    private fun respondHttp(out: java.io.OutputStream, status: Int, reason: String, keepAlive: Boolean) {
        val connection = if (keepAlive) "" else "Connection: close\r\n"
        out.write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\n$connection\r\n".toByteArray())
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    // --- IPP ----------------------------------------------------------------------------------

    private fun status(code: Int, requestId: Int, message: String? = null, extra: List<IppAttribute> = emptyList()): IppMessage =
        ippMessage(code, requestId) {
            group(GroupTag.OPERATION_ATTRIBUTES) {
                charset("attributes-charset", "utf-8")
                naturalLanguage("attributes-natural-language", "en")
                message?.let { text("status-message", it) }
            }
            if (extra.isNotEmpty()) group(GroupTag.UNSUPPORTED_ATTRIBUTES) { extra.forEach(::add) }
        }

    private fun dispatch(request: IppMessage, document: ByteArray): IppMessage {
        val op = request.attribute(GroupTag.OPERATION_ATTRIBUTES, "attributes-charset")
        if (op == null) return status(IppStatus.CLIENT_ERROR_BAD_REQUEST, request.requestId, "missing attributes-charset")
        behavior.nextIppStatus?.let {
            behavior.nextIppStatus = null
            return status(it, request.requestId, "injected")
        }
        return when (request.code) {
            IppOperation.GET_PRINTER_ATTRIBUTES -> getPrinterAttributes(request)
            IppOperation.VALIDATE_JOB -> validateOrPrint(request, document, print = false)
            IppOperation.PRINT_JOB -> validateOrPrint(request, document, print = true)
            IppOperation.GET_JOB_ATTRIBUTES -> getJobAttributes(request)
            IppOperation.CANCEL_JOB -> cancelJob(request)
            else -> status(IppStatus.SERVER_ERROR_OPERATION_NOT_SUPPORTED, request.requestId)
        }
    }

    private fun opAttr(request: IppMessage, name: String) = request.attribute(GroupTag.OPERATION_ATTRIBUTES, name)

    private fun getPrinterAttributes(request: IppMessage): IppMessage {
        val requested = opAttr(request, "requested-attributes")?.strings()
        val all = profile.printerAttributes(uri).map {
            when (it.name) {
                "printer-state" -> IppAttribute("printer-state", io.github.zsozso01.platen.protocol.ipp.IppEnum(behavior.printerState))
                "printer-state-reasons" -> IppAttribute("printer-state-reasons", behavior.printerStateReasons.map { r -> IppString(IppString.Kind.KEYWORD, r) })
                else -> it
            }
        }
        val chosen = if (requested == null || requested.any { it == "all" || it == "printer-description" || it == "job-template" }) {
            all
        } else {
            all.filter { it.name in requested }
        }
        return ippMessage(IppStatus.SUCCESSFUL_OK, request.requestId) {
            group(GroupTag.OPERATION_ATTRIBUTES) {
                charset("attributes-charset", "utf-8")
                naturalLanguage("attributes-natural-language", "en")
            }
            group(GroupTag.PRINTER_ATTRIBUTES) { chosen.forEach(::add) }
        }
    }

    private fun validateOrPrint(request: IppMessage, document: ByteArray, print: Boolean): IppMessage {
        val format = opAttr(request, "document-format")?.string()
        if (format != null && format != "application/octet-stream" && format !in profile.documentFormats) {
            return status(
                IppStatus.CLIENT_ERROR_DOCUMENT_FORMAT_NOT_SUPPORTED,
                request.requestId,
                "document format $format not supported",
                listOf(IppAttribute("document-format", IppString(IppString.Kind.MIME_MEDIA_TYPE, format))),
            )
        }
        val jobAttrs = request.group(GroupTag.JOB_ATTRIBUTES)?.attributes.orEmpty()
        behavior.rejectAttribute?.let { name ->
            jobAttrs.firstOrNull { it.name == name }?.let { offending ->
                return status(IppStatus.CLIENT_ERROR_ATTRIBUTES_OR_VALUES_NOT_SUPPORTED, request.requestId, "attribute not supported", listOf(offending))
            }
        }
        val unsupported = jobAttrs.filter { it.name == "sides" && it.string() !in profile.sides }
        if (!print) {
            return status(if (unsupported.isEmpty()) IppStatus.SUCCESSFUL_OK else IppStatus.SUCCESSFUL_OK_IGNORED_OR_SUBSTITUTED, request.requestId, extra = unsupported)
        }
        val id = nextJobId.getAndIncrement()
        jobs += ReceivedJob(id, opAttr(request, "job-name")?.string(), format, jobAttrs, document)
        return ippMessage(if (unsupported.isEmpty()) IppStatus.SUCCESSFUL_OK else IppStatus.SUCCESSFUL_OK_IGNORED_OR_SUBSTITUTED, request.requestId) {
            group(GroupTag.OPERATION_ATTRIBUTES) {
                charset("attributes-charset", "utf-8")
                naturalLanguage("attributes-natural-language", "en")
            }
            group(GroupTag.JOB_ATTRIBUTES) {
                integer("job-id", id)
                uri("job-uri", "$uri/jobs/$id")
                enum("job-state", 3)
                keywords("job-state-reasons", listOf("none"))
            }
        }
    }

    private fun getJobAttributes(request: IppMessage): IppMessage {
        val id = opAttr(request, "job-id")?.int() ?: return status(IppStatus.CLIENT_ERROR_BAD_REQUEST, request.requestId, "no job-id")
        if (jobs.none { it.id == id }) return status(IppStatus.CLIENT_ERROR_NOT_FOUND, request.requestId, "no such job")
        val pollCount = polls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
        val state = when {
            id in canceled -> 7
            behavior.jobStopped -> 6
            pollCount > behavior.pollsUntilCompleted -> 9
            else -> 5
        }
        return ippMessage(IppStatus.SUCCESSFUL_OK, request.requestId) {
            group(GroupTag.OPERATION_ATTRIBUTES) {
                charset("attributes-charset", "utf-8")
                naturalLanguage("attributes-natural-language", "en")
            }
            group(GroupTag.JOB_ATTRIBUTES) {
                integer("job-id", id)
                enum("job-state", state)
                keywords("job-state-reasons", listOf(if (state == 9) "job-completed-successfully" else if (state == 7) "job-canceled-by-user" else "job-printing"))
                integer("job-media-sheets-completed", if (state == 9) 1 else 0)
            }
        }
    }

    private fun cancelJob(request: IppMessage): IppMessage {
        val id = opAttr(request, "job-id")?.int() ?: return status(IppStatus.CLIENT_ERROR_BAD_REQUEST, request.requestId, "no job-id")
        if (jobs.none { it.id == id }) return status(IppStatus.CLIENT_ERROR_NOT_FOUND, request.requestId, "no such job")
        canceled += id
        return status(IppStatus.SUCCESSFUL_OK, request.requestId)
    }
}
