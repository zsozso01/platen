package io.github.zsozso01.platen.transport.usb

import io.github.zsozso01.platen.protocol.ipp.IppClient
import io.github.zsozso01.platen.protocol.ipp.IppHttpTransport
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.testing.usb.SimulatedUsbHost
import io.github.zsozso01.platen.testing.usb.SimulatedUsbPrinterAccess
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostUsbPrinterAccessTest {
    private val ipp = FakeIppPrinter(FakePrinterProfile.laserPdf).also { it.behavior.requiredHost = "localhost" }
    private val pjl = FakePjlPrinter()
    private val descriptors = SimulatedUsbPrinterAccess.hpLaserDescriptors()
    private val deviceId = "MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PDF;"
    private val hosts = mutableListOf<SimulatedUsbHost>()

    @AfterTest
    fun tearDown() {
        hosts.forEach(SimulatedUsbHost::close)
        ipp.close()
        pjl.close()
    }

    private fun host(device: UsbDeviceInfo = descriptors) = SimulatedUsbHost(device, ipp, pjl, deviceId).also { hosts += it }

    private fun access(host: SimulatedUsbHost, writeTimeout: Int = 5_000) = HostUsbPrinterAccess(host.device, host, writeTimeoutMillis = writeTimeout)

    private fun ippPlan(device: UsbDeviceInfo = descriptors) = assertIs<UsbPrinterPlan.IppUsb>(UsbInterfacePlanner.best(device))

    private fun legacyPlan(device: UsbDeviceInfo = descriptors) = UsbInterfacePlanner.plans(device).filterIsInstance<UsbPrinterPlan.Legacy>().first()

    // --- claiming ---------------------------------------------------------------------------------

    @Test
    fun `an interface is claimed before its alternate setting is selected`() {
        val host = host()
        access(host).openIppPipes(ippPlan())
        assertEquals(listOf("claim 0", "alt 0/1", "claim 1", "alt 1/0"), host.log.toList())
    }

    @Test
    fun `a failing claim releases what was already taken and says why`() {
        val host = host().also { it.claimFailsFor = 1 }
        val e = assertFailsWith<IOException> { access(host).openIppPipes(ippPlan()) }
        assertTrue("claim USB interface 1" in e.message.orEmpty(), e.message)
        assertEquals(1, host.releases[0]?.get(), "interface 0 was given back")
    }

    @Test
    fun `closing a pipe releases its interface once`() {
        val host = host()
        val pipes = access(host).openIppPipes(ippPlan())
        pipes.forEach { it.close(); it.close() }
        assertEquals(1, host.releases[0]?.get())
        assertEquals(1, host.releases[1]?.get())
    }

    // --- IPP over the simulated host --------------------------------------------------------------

    @Test
    fun `IPP works through the host with packet-sized reads`() {
        val host = host()
        val connector = IppUsbConnector(access(host).openIppPipes(ippPlan()), acquireTimeoutMillis = 2_000, silenceTimeoutMillis = 3_000, sliceMillis = 20)
        val client = IppClient(IppHttpTransport(connector, "localhost", "/ipp/print", sendConnectionClose = false, allowReadToEnd = false), "ipp://localhost/ipp/print")
        assertEquals("Fake LaserJet PDF", client.getPrinterAttributes().name)
        assertTrue(host.maxReadRequest.get() <= 512, "reads were asked for ${host.maxReadRequest.get()} bytes; a packet is 512")
        connector.close()
    }

    @Test
    fun `an exchange cut short is repaired with a soft reset`() {
        val host = host()
        val pipe = access(host).openIppPipes(ippPlan()).first()
        val half = "POST /ipp/print HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\nonly part of the body".toByteArray()
        pipe.write(half, 0, half.size)
        assertTrue(pipe.recover())
        assertEquals(1, host.softResets.get())
        assertEquals("control 0x23 req 2 idx 0", host.log.first { it.startsWith("control") })
        // The pipe is usable again.
        val connector = IppUsbConnector(listOf(pipe), acquireTimeoutMillis = 1_000, silenceTimeoutMillis = 3_000, sliceMillis = 20)
        val client = IppClient(IppHttpTransport(connector, "localhost", "/ipp/print", sendConnectionClose = false, allowReadToEnd = false), "ipp://localhost/ipp/print")
        assertEquals("Fake LaserJet PDF", client.getPrinterAttributes().name)
    }

    @Test
    fun `a printer that wants the soft reset sent to the interface is still reset`() {
        val host = host().also { it.softResetTypes = setOf(0x21) }
        val pipe = access(host).openIppPipes(ippPlan()).first()
        assertTrue(pipe.recover())
        assertEquals(1, host.softResets.get())
        assertEquals(listOf("control 0x23 req 2 idx 0", "control 0x21 req 2 idx 0"), host.log.filter { it.startsWith("control") }.take(2))
    }

    // --- reads, writes and failures -----------------------------------------------------------------

    @Test
    fun `a quiet printer is a timeout, an unplugged one is an error`() {
        val host = host()
        val pipe = access(host).openIppPipes(ippPlan()).first()
        val buffer = ByteArray(4096)
        assertEquals(0, pipe.read(buffer, 0, buffer.size, 60))
        host.detach()
        assertFailsWith<IOException> { pipe.read(buffer, 0, buffer.size, 60) }
        assertFailsWith<IOException> { pipe.write(buffer, 0, 10) }
    }

    @Test
    fun `a printer that stops reading ends the write instead of hanging`() {
        val host = host().also { it.stallWrites = true }
        val pipe = access(host, writeTimeout = 120).openIppPipes(ippPlan()).first()
        val e = assertFailsWith<IOException> { pipe.write(ByteArray(100), 0, 100) }
        assertTrue("stopped accepting data" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a write failing at once is an error rather than a stall`() {
        val host = host()
        val pipe = access(host).openIppPipes(ippPlan()).first()
        host.detach()
        val e = assertFailsWith<IOException> { pipe.write(ByteArray(100), 0, 100) }
        assertTrue("unplugged" in e.message.orEmpty() || "failed" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `large writes are split into chunks`() {
        val host = host(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        val channel = HostUsbPrinterAccess(host.device, host, writeChunkBytes = 1024).openLegacy(legacyPlan(host.device))
        val data = ByteArray(5_000) { it.toByte() }
        val builder = io.github.zsozso01.platen.protocol.pjl.PjlJobBuilder()
        val job = builder.header(io.github.zsozso01.platen.protocol.pjl.Pjl.Language.PDF) + data + builder.footer()
        channel.write(job, 0, job.size)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (pjl.jobs.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        // The fake records a job once it has seen the end of it, so the bytes arrived complete and in order.
        assertContentEquals(data, pjl.jobs.firstOrNull()?.data ?: ByteArray(0))
    }

    // --- legacy interface and Device ID ----------------------------------------------------------------

    @Test
    fun `the classic interface answers PJL queries through the host`() {
        val host = host(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        val channel = access(host).openLegacy(legacyPlan(host.device))
        val query = io.github.zsozso01.platen.protocol.pjl.PjlQueries.query("INFO ID")
        channel.write(query, 0, query.size)
        val received = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline && !io.github.zsozso01.platen.protocol.pjl.PjlResponseParser.isComplete(received.toByteArray())) {
            val n = channel.read(buffer, 0, buffer.size, 100)
            if (n > 0) received.write(buffer, 0, n)
        }
        val responses = io.github.zsozso01.platen.protocol.pjl.PjlResponseParser.parse(received.toByteArray())
        assertEquals("Fake LaserJet", responses.first { it.command == "INFO ID" }.text)
        channel.close()
        assertEquals(1, host.releases[0]?.get())
    }

    @Test
    fun `the device id is read with the class request and parsed whichever way round its length is`() {
        val big = host()
        assertEquals(deviceId, access(big).readDeviceId(legacyPlan().iface))
        assertTrue(big.log.any { it == "control 0xa1 req 0 idx 0" }, "wIndex is interface << 8 | alternate: ${big.log}")

        val little = host().also { it.deviceIdLittleEndian = true }
        assertEquals(deviceId, access(little).readDeviceId(legacyPlan().iface))
    }

    @Test
    fun `an unreadable device id is null, not an exception`() {
        val none = SimulatedUsbHost(descriptors, ipp, pjl, deviceIdText = null).also { hosts += it }
        assertNull(access(none).readDeviceId(legacyPlan().iface))
        val gone = host().also { it.detach() }
        assertNull(access(gone).readDeviceId(legacyPlan().iface))
    }

    @Test
    fun `after the printer is unplugged nothing can be claimed`() {
        val host = host()
        val access = access(host)
        access.markDetached()
        assertFailsWith<IOException> { access.openIppPipes(ippPlan()) }
        assertFalse(host.log.any { it.startsWith("claim") }, "no claim was even attempted")
    }
}
