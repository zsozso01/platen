package io.github.zsozso01.platen.route.ipp

import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.transport.network.PrinterAddress
import io.github.zsozso01.platen.transport.network.TcpConnector
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IppAddressProbeTest {
    private val printers = mutableListOf<FakeIppPrinter>()

    @AfterTest
    fun tearDown() = printers.forEach(FakeIppPrinter::close)

    private fun fake(path: String) = FakeIppPrinter(FakePrinterProfile.inkjetRasterOnly, path = path).also(printers::add)

    private fun probe(text: String) = IppAddressProbe.probe(PrinterAddress.parse(text)!!) { TcpConnector(it.host, it.port, connectTimeoutMillis = 1_000) }

    @Test
    fun `a bare host finds the printer at whichever usual path it uses`() {
        for (path in listOf("/ipp/print", "/ipp/printer", "/ipp", "/")) {
            val printer = fake(path)
            val found = probe("127.0.0.1:${printer.port}")
            assertEquals(path, found.path)
            assertEquals("ipp://127.0.0.1:${printer.port}$path", found.printerUri)
            assertEquals("Fake Inkjet 3700 series", found.probe.makeAndModel)
            assertTrue(found.probe.capabilities.supportsPageRanges)
        }
    }

    @Test
    fun `an explicit path is used as given`() {
        val printer = fake("/ipp/print")
        assertEquals("/ipp/print", probe("ipp://127.0.0.1:${printer.port}/ipp/print").path)
        val e = assertFailsWith<AddressProbeException> { probe("ipp://127.0.0.1:${printer.port}/nope") }
        assertEquals(AddressProbeException.Reason.NOT_A_PRINTER, e.reason)
    }

    @Test
    fun `nothing listening is unreachable and quick`() {
        val closed = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val started = System.currentTimeMillis()
        val e = assertFailsWith<AddressProbeException> { probe("127.0.0.1:$closed") }
        assertEquals(AddressProbeException.Reason.UNREACHABLE, e.reason)
        assertTrue(System.currentTimeMillis() - started < 3_000, "must not try every path against a dead host")
    }

    @Test
    fun `encryption and authentication demands are told apart`() {
        val printer = fake("/ipp/print")
        printer.behavior.httpStatus = 426
        assertEquals(AddressProbeException.Reason.ENCRYPTION_REQUIRED, assertFailsWith<AddressProbeException> { probe("127.0.0.1:${printer.port}") }.reason)
        printer.behavior.httpStatus = 401
        assertEquals(AddressProbeException.Reason.AUTHENTICATION_REQUIRED, assertFailsWith<AddressProbeException> { probe("127.0.0.1:${printer.port}") }.reason)
    }

    @Test
    fun `ipps is refused with an explanation`() {
        val e = assertFailsWith<AddressProbeException> { probe("ipps://127.0.0.1") }
        assertEquals(AddressProbeException.Reason.SECURE_NOT_SUPPORTED, e.reason)
    }

    @Test
    fun `something that is not a printer is reported as such`() {
        // A TCP server that speaks gibberish.
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val thread = Thread {
            while (!server.isClosed) {
                runCatching { server.accept().use { it.getOutputStream().write("HELLO, I AM NOT HTTP\r\n\r\n".toByteArray()) } }
            }
        }.apply { isDaemon = true; start() }
        try {
            val e = assertFailsWith<AddressProbeException> { probe("127.0.0.1:${server.localPort}") }
            assertTrue(e.reason == AddressProbeException.Reason.NOT_A_PRINTER || e.reason == AddressProbeException.Reason.UNREACHABLE, "${e.reason}")
        } finally {
            server.close()
            thread.join(1_000)
        }
    }
}
