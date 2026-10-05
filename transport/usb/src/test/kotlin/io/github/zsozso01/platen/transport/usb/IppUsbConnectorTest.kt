package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.protocol.ipp.IppClient
import io.github.zsozso01.platen.protocol.ipp.IppDocument
import io.github.zsozso01.platen.protocol.ipp.IppEncoder
import io.github.zsozso01.platen.protocol.ipp.IppHttpException
import io.github.zsozso01.platen.protocol.ipp.IppHttpTransport
import io.github.zsozso01.platen.protocol.ipp.IppOperation
import io.github.zsozso01.platen.protocol.ipp.ippMessage
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.testing.usb.SimulatedIppUsbDevice
import io.github.zsozso01.platen.route.ipp.IppEndpoint
import io.github.zsozso01.platen.route.ipp.IppJobProtocol
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IppUsbConnectorTest {
    private val printer = FakeIppPrinter(FakePrinterProfile.laserPdf).also { it.behavior.requiredHost = "localhost" }
    private var device: SimulatedIppUsbDevice? = null

    @AfterTest
    fun tearDown() {
        device?.close()
        printer.close()
    }

    private fun usb(interfaces: Int = 2, silenceMillis: Long = 5_000, acquireMillis: Long = 5_000): Pair<IppUsbConnector, SimulatedIppUsbDevice> {
        val d = SimulatedIppUsbDevice(printer, interfaces).also { device = it }
        return IppUsbConnector(d.pipes, acquireTimeoutMillis = acquireMillis, silenceTimeoutMillis = silenceMillis, sliceMillis = 20) to d
    }

    private fun client(connector: IppUsbConnector) = IppClient(
        IppHttpTransport(connector, "localhost", "/ipp/print", sendConnectionClose = false, allowReadToEnd = false),
        "ipp://localhost/ipp/print",
    )

    @Test
    fun `a request over USB is answered, uses Host localhost, sends no Connection header and leaves the pipe clean`() {
        val (connector, d) = usb()
        val attrs = client(connector).getPrinterAttributes()
        assertEquals("Fake LaserJet PDF", attrs.name)
        val headers = printer.requestHeaders.single()
        assertEquals("localhost", headers["host"])
        assertFalse("connection" in headers, "IPP-USB must not send Connection: close")
        d.pipes.forEach {
            assertEquals(0, it.staleBytesForHost)
            assertEquals(0, it.recoveries.get(), "a clean exchange needs no recovery")
        }
    }

    @Test
    fun `without Host localhost this printer refuses, which is why the endpoint must say localhost`() {
        val (connector, _) = usb()
        val wrongHost = IppClient(IppHttpTransport(connector, "printer.local", "/ipp/print", sendConnectionClose = false, allowReadToEnd = false), "ipp://printer.local/ipp/print")
        assertEquals(400, assertFailsWith<IppHttpException> { wrongHost.getPrinterAttributes() }.status)
    }

    @Test
    fun `many requests in a row never need recovery`() {
        val (connector, d) = usb()
        val client = client(connector)
        repeat(30) { assertNotNull(client.getPrinterAttributes().name) }
        d.pipes.forEach { assertEquals(0, it.recoveries.get()) }
        assertEquals(2, connector.usablePipes)
    }

    @Test
    fun `a long upload on one interface does not stop status requests on the other`() {
        val (connector, d) = usb()
        d.pipes[0].writeDelayMillis = 10
        d.pipes[1].writeDelayMillis = 10
        val client = client(connector)
        val doc = Random(3).nextBytes(1_500_000) // about 92 chunks of 16 KiB: roughly a second at 10 ms each
        val uploadDone = AtomicReference<Long>()
        val start = System.nanoTime()
        val upload = thread {
            client.printJob("application/pdf", "big", emptyList(), IppDocument(ByteArrayInputStream(doc), doc.size.toLong()))
            uploadDone.set(System.nanoTime())
        }
        Thread.sleep(150) // the upload is under way and holds one interface
        client.getPrinterAttributes()
        val statusDone = System.nanoTime()
        upload.join(20_000)
        assertNotNull(uploadDone.get(), "the upload finished")
        assertTrue(statusDone < uploadDone.get(), "status was answered while the upload was still running (${(statusDone - start) / 1_000_000} ms vs ${(uploadDone.get() - start) / 1_000_000} ms)")
        assertEquals(doc.size, printer.jobs.single().document.size)
        assertTrue(d.pipes.all { it.writeCalls.get() > 0 }, "both interfaces were used")
    }

    @Test
    fun `a request cut short before its response was read triggers recovery and the interface works again`() {
        val (connector, d) = usb(interfaces = 1)
        val request = IppEncoder.encode(
            ippMessage(IppOperation.GET_PRINTER_ATTRIBUTES) {
                group(io.github.zsozso01.platen.protocol.ipp.GroupTag.OPERATION_ATTRIBUTES) {
                    charset("attributes-charset", "utf-8")
                    naturalLanguage("attributes-natural-language", "en")
                    uri("printer-uri", "ipp://localhost/ipp/print")
                }
            },
        )
        val http = ("POST /ipp/print HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/ipp\r\nContent-Length: ${request.size}\r\n\r\n").toByteArray() + request
        val c = connector.connect()
        c.output.write(http)
        Thread.sleep(250) // the printer answers; the host walks away without reading
        c.close()
        assertEquals(1, d.pipes[0].recoveries.get())
        assertEquals(0, d.pipes[0].staleBytesForHost, "recovery discards the unread answer")
        // The same, only, interface answers a normal request cleanly afterwards.
        assertEquals("Fake LaserJet PDF", client(connector).getPrinterAttributes().name)
    }

    @Test
    fun `an interface that cannot be recovered is retired and when none are left connecting fails`() {
        val (connector, d) = usb(interfaces = 1)
        d.pipes[0].recoveryWorks = false
        val c = connector.connect()
        c.close() // closed without a completed response: recovery is attempted and fails
        assertEquals(0, connector.usablePipes)
        val e = assertFailsWith<IOException> { connector.connect() }
        assertTrue("not available" in e.message!!)
    }

    @Test
    fun `a printer that never answers is reported as silent and the interface is recovered`() {
        printer.behavior.delayMillis = 1_500
        val (connector, d) = usb(interfaces = 1, silenceMillis = 300)
        val e = assertFailsWith<IOException> { client(connector).getPrinterAttributes() }
        assertTrue("did not answer" in e.message!!, e.message)
        assertEquals(1, d.pipes[0].recoveries.get())
        printer.behavior.delayMillis = 0
        assertNotNull(client(connector).getPrinterAttributes().name, "usable again after recovery")
    }

    @Test
    fun `cancelling while a write is stuck interrupts it quickly with a soft reset`() {
        val (connector, d) = usb(interfaces = 1)
        d.pipes[0].blockWrites = true // the printer's buffer is full and it is not taking data
        val c = connector.connect()
        val failure = AtomicReference<Throwable>()
        val writer = thread { runCatching { c.output.write(ByteArray(64 * 1024)) }.onFailure(failure::set) }
        Thread.sleep(200)
        val started = System.nanoTime()
        c.close() // what a cancel does from another thread
        writer.join(3_000)
        assertFalse(writer.isAlive, "the stuck writer was released")
        assertEquals(1, d.pipes[0].aborts.get())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun `when every interface is busy a new request waits and then gives up`() {
        val (connector, _) = usb(interfaces = 1, acquireMillis = 200)
        val first = connector.connect()
        val e = assertFailsWith<IOException> { connector.connect() }
        assertTrue("busy" in e.message!!)
        first.responseCompleted()
        first.close()
        connector.connect().close() // available again once released
    }

    @Test
    fun `a complete IPP job runs over simulated USB through the real job protocol`() {
        val (connector, d) = usb()
        val protocol = IppJobProtocol(
            IppEndpoint(connector, "localhost", "/ipp/print", "ipp://localhost/ipp/print", persistentConnection = true),
            pollIntervalMillis = 10,
            sleeper = { Thread.sleep(1) },
        )
        val probe = protocol.probe()
        assertTrue(probe.capabilities.supports(DocumentFormat.PDF))

        val file = File.createTempFile("job", ".pdf").apply { writeBytes("%PDF-1.7 hello over usb".toByteArray()); deleteOnExit() }
        val events = mutableListOf<JobEvent>()
        protocol.submit(JobSubmission("usb job", DocumentFormat.PDF, file, PrinterJobSettings("usb job", copies = 2), null), CancelToken()) { events += it }
        assertEquals(JobEvent.Completed, events.last())
        val job = printer.jobs.single()
        assertEquals("%PDF-1.7 hello over usb", String(job.document))
        assertEquals(2, job.jobAttributes.first { it.name == "copies" }.int())
        d.pipes.forEach { assertEquals(0, it.staleBytesForHost) }
        assertEquals(2, connector.usablePipes, "no interface was lost along the way")
    }
}
