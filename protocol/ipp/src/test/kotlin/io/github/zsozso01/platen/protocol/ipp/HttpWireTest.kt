package io.github.zsozso01.platen.protocol.ipp

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpWireTest {
    private fun read(text: String, max: Int = 1024) = HttpWire.readResponse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), max)

    @Test
    fun `content-length body`() {
        val r = read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Type: application/ipp\r\n\r\nhello")
        assertEquals(200, r.status)
        assertEquals("OK", r.reason)
        assertEquals("application/ipp", r.headers["content-type"])
        assertContentEquals("hello".toByteArray(), r.body)
    }

    @Test
    fun `chunked body with extension and trailer`() {
        val r = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3;ext=1\r\nabc\r\n2\r\nde\r\n0\r\nX-Trailer: 1\r\n\r\n")
        assertContentEquals("abcde".toByteArray(), r.body)
    }

    @Test
    fun `interim 100 Continue is skipped`() {
        val r = read("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok")
        assertEquals(200, r.status)
        assertContentEquals("ok".toByteArray(), r.body)
    }

    @Test
    fun `body until close when no length is given`() {
        assertContentEquals("tail".toByteArray(), read("HTTP/1.0 200 OK\r\n\r\ntail").body)
    }

    @Test
    fun `bare LF line endings are accepted`() {
        assertEquals(200, read("HTTP/1.1 200 OK\nContent-Length: 0\n\n").status)
    }

    @Test
    fun `204 has no body even without length`() {
        assertEquals(0, read("HTTP/1.1 204 No Content\r\n\r\n").body.size)
    }

    @Test
    fun `non-HTTP garbage is an IOException`() {
        assertFailsWith<IOException> { read("\u0001\u0002 not http\r\n\r\n") }
        assertFailsWith<IOException> { read("") }
        assertFailsWith<IOException> { read("HTTP/1.1 abc OK\r\n\r\n") }
    }

    @Test
    fun `truncated bodies are IOExceptions`() {
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort") }
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nab") }
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nContent-Len") }
    }

    @Test
    fun `oversized bodies are refused`() {
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nContent-Length: 999999999\r\n\r\n", max = 100) }
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n0123456789abcdef\r\n0\r\n\r\n", max = 8) }
        assertFailsWith<IOException> { read("HTTP/1.0 200 OK\r\n\r\n" + "x".repeat(200), max = 100) }
    }

    @Test
    fun `bad chunk sizes are IOExceptions`() {
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n") }
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-5\r\nabc\r\n") }
    }

    @Test
    fun `very long lines are refused`() {
        assertFailsWith<IOException> { read("HTTP/1.1 200 OK\r\nX: " + "a".repeat(20_000) + "\r\n\r\n") }
    }
}
