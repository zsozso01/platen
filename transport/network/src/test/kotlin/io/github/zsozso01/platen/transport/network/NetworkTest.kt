package io.github.zsozso01.platen.transport.network

import io.github.zsozso01.platen.protocol.ipp.IppClient
import io.github.zsozso01.platen.protocol.ipp.IppHttpTransport
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkTest {
    @Test
    fun `parses what people type`() {
        assertEquals(PrinterAddress("192.168.1.20", 631, null, false), PrinterAddress.parse("192.168.1.20"))
        assertEquals(PrinterAddress("printer.local", 631, null, false), PrinterAddress.parse("  printer.local "))
        assertEquals(PrinterAddress("10.0.2.2", 6310, null, false), PrinterAddress.parse("10.0.2.2:6310"))
        assertEquals(PrinterAddress("hp1234.local", 631, "/ipp/print", false), PrinterAddress.parse("ipp://hp1234.local/ipp/print"))
        assertEquals(PrinterAddress("hp", 8631, "/ipp/print", false), PrinterAddress.parse("ipp://hp:8631/ipp/print"))
        assertEquals(PrinterAddress("hp", 443, "/ipp/print", true), PrinterAddress.parse("ipps://hp/ipp/print"))
        assertEquals(PrinterAddress("hp", 631, "/", false), PrinterAddress.parse("http://hp/"))
        assertEquals(PrinterAddress("fe80::1", 631, null, false), PrinterAddress.parse("[fe80::1]"))
        assertEquals(PrinterAddress("fe80::1", 6310, null, false), PrinterAddress.parse("[fe80::1]:6310"))
    }

    @Test
    fun `rejects things that are not addresses`() {
        listOf("", "   ", "two words", "ftp://printer", "host:abc", "host:0", "host:70000", "://x", "ipp://", "ho st", "a/b c").forEach {
            assertNull(PrinterAddress.parse(it), "'$it'")
        }
    }

    @Test
    fun `builds the printer uri and host header`() {
        val a = PrinterAddress.parse("10.0.2.2:6310")!!
        assertEquals("ipp://10.0.2.2:6310/ipp/print", a.printerUri("/ipp/print"))
        assertEquals("10.0.2.2:6310", a.hostHeader)
        assertEquals("10.0.2.2", PrinterAddress.parse("10.0.2.2")!!.hostHeader)
        assertEquals("ipps://hp:443/x", PrinterAddress.parse("ipps://hp")!!.printerUri("/x"))
    }

    @Test
    fun `the tcp connector reaches a real printer and fails fast on a closed port`() {
        FakeIppPrinter(FakePrinterProfile.laserPdf).use { printer ->
            val connector = TcpConnector("127.0.0.1", printer.port)
            val client = IppClient(IppHttpTransport(connector, "127.0.0.1:${printer.port}", printer.path), printer.uri)
            assertNotNull(client.getPrinterAttributes().name)
        }
        val closed = ServerSocket(0).use { it.localPort } // nothing listens here any more
        val started = System.currentTimeMillis()
        assertFailsWith<IOException> { TcpConnector("127.0.0.1", closed, connectTimeoutMillis = 2_000).connect() }
        assertTrue(System.currentTimeMillis() - started < 3_000)
    }
}
