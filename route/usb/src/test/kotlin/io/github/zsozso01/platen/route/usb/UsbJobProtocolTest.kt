package io.github.zsozso01.platen.route.usb

import io.github.zsozso01.platen.core.engine.ByteChannel
import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.route.pjl.PjlTiming
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.testing.usb.SimulatedUsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.IppUsbConnector
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import io.github.zsozso01.platen.transport.usb.UsbInterfaceInfo
import io.github.zsozso01.platen.transport.usb.UsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.UsbPrinterPlan
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UsbJobProtocolTest {
    private val ipp = FakeIppPrinter(FakePrinterProfile.laserPdf).also { it.behavior.requiredHost = "localhost" }
    private val pjl = FakePjlPrinter()
    private val spool: File = createTempDirectory("platen-usb").toFile()
    private val traces = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        ipp.close()
        pjl.close()
        spool.deleteRecursively()
    }

    private val deviceId = "MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF;CLS:PRINTER;"

    private fun access(descriptors: UsbDeviceInfo = SimulatedUsbPrinterAccess.hpLaserDescriptors()) =
        SimulatedUsbPrinterAccess(descriptors, ipp, pjl, deviceId)

    private fun protocol(
        access: UsbPrinterAccess,
        mode: UsbMode = UsbMode.AUTO,
        busyRetries: Int = 3,
        sleeper: (Long) -> Unit = { Thread.sleep(20) },
    ) = UsbJobProtocol(
        access,
        mode = mode,
        pjlTiming = PjlTiming(readSliceMillis = 10, flushMillis = 5, queryFirstByteMillis = 300, statusPollMillis = 40, settleMillis = 30, maxSilentMillis = 400, writeChunkBytes = 64),
        ippPollIntervalMillis = 10,
        busyRetries = busyRetries,
        busyDelayMillis = 20,
        sleeper = sleeper,
        connectorFactory = { IppUsbConnector(it, acquireTimeoutMillis = 2_000, silenceTimeoutMillis = 2_000, sliceMillis = 20) },
        trace = { traces += it },
    )

    private fun submission(payload: ByteArray = "%PDF-1.7 hello".toByteArray()): JobSubmission {
        val settings = PrinterJobSettings(jobName = "Doc")
        val file = File.createTempFile("job", ".pdf", spool).also { it.writeBytes(payload) }
        return JobSubmission("Doc", DocumentFormat.PDF, file, settings, raster = null)
    }

    private fun run(protocol: UsbJobProtocol, submission: JobSubmission = submission()): List<JobEvent> {
        val events = CopyOnWriteArrayList<JobEvent>()
        protocol.submit(submission, CancelToken()) { events += it }
        return events
    }

    // --- choosing a route -------------------------------------------------------------------------------

    @Test
    fun `a printer with IPP over USB is driven over IPP and its interfaces are released afterwards`() {
        val access = access()
        val protocol = protocol(access)
        val probe = protocol.probe()
        assertEquals("Fake LaserJet PDF", probe.makeAndModel, "described by IPP, not PJL")
        assertEquals("ipp-usb", protocol.routeName)
        assertEquals(0, access.legacyClaims.get())
        assertTrue(access.ippDevices.single().pipes.all { it.closed }, "the interfaces were released")

        val events = run(protocol)
        assertEquals(JobEvent.Completed, events.last())
        assertEquals("%PDF-1.7 hello", String(ipp.jobs.single().document))
        assertTrue(pjl.jobs.isEmpty())
        assertTrue(access.ippDevices.all { d -> d.pipes.all { it.closed } })
    }

    @Test
    fun `when the IPP interfaces cannot be claimed the classic interface with PJL is used`() {
        val access = access().also { it.ippClaimFails = true }
        val protocol = protocol(access)
        val probe = protocol.probe()
        assertEquals("Fake LaserJet", probe.makeAndModel)
        assertEquals("pjl", protocol.routeName)
        assertEquals(1, access.ippClaims.get())
        assertTrue(traces.any { "IPP over USB" in it && "failed" in it }, "the fallback is recorded for diagnostics: $traces")

        assertEquals(JobEvent.Completed, run(protocol).last())
        assertEquals(1, pjl.jobs.size)
        assertTrue(ipp.jobs.isEmpty())
    }

    @Test
    fun `a classic-only printer goes straight to PJL`() {
        val access = access(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        val protocol = protocol(access)
        protocol.probe()
        assertEquals("pjl", protocol.routeName)
        assertEquals(0, access.ippClaims.get())
    }

    @Test
    fun `a single IPP interface is not IPP over USB`() {
        val one = UsbInterfaceInfo(0, 1, 0, 7, 1, UsbInterfaceInfo.PROTOCOL_IPP_USB, SimulatedUsbPrinterAccess.hpLaserDescriptors().interfaces[0].endpoints)
        val d = SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false)
        val access = access(d.copy(interfaces = d.interfaces + one))
        val protocol = protocol(access)
        protocol.probe()
        assertEquals("pjl", protocol.routeName)
        assertEquals(0, access.ippClaims.get())
    }

    @Test
    fun `a user-chosen mode is respected and does not fall back`() {
        val access = access()
        val pjlOnly = protocol(access, UsbMode.PJL)
        assertEquals("Fake LaserJet", pjlOnly.probe().makeAndModel)
        assertEquals(0, access.ippClaims.get())

        val broken = access().also { it.ippClaimFails = true }
        val ippOnly = protocol(broken, UsbMode.IPP_USB)
        val e = assertFailsWith<IOException> { ippOnly.probe() }
        assertTrue("IPP over USB" in e.message.orEmpty(), e.message)
        assertEquals(0, broken.legacyClaims.get())
    }

    @Test
    fun `a device with no printer interface is explained`() {
        val none = access(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false, legacy = false))
        val protocol = protocol(none)
        assertTrue("no printer interface" in assertFailsWith<IOException> { protocol.probe() }.message.orEmpty())
        val failed = assertIs<JobEvent.Failed>(run(protocol).single())
        assertIs<PrintFailure.UsbProblem>(failed.failure)
    }

    // --- a printer that is still starting up --------------------------------------------------------------------

    @Test
    fun `a printer that answers 503 while it starts is waited for`() {
        ipp.behavior.httpStatus = 503
        thread { Thread.sleep(150); ipp.behavior.httpStatus = null }
        val protocol = protocol(access(), busyRetries = 20)
        assertEquals("Fake LaserJet PDF", protocol.probe().makeAndModel)
        assertEquals("ipp-usb", protocol.routeName)
        assertTrue(traces.any { "busy (503)" in it })
    }

    @Test
    fun `a printer that stays busy falls back to PJL after the retries`() {
        ipp.behavior.httpStatus = 503
        val protocol = protocol(access(), busyRetries = 2)
        assertEquals("Fake LaserJet", protocol.probe().makeAndModel)
        assertEquals("pjl", protocol.routeName)
    }

    // --- jobs ---------------------------------------------------------------------------------------------------

    @Test
    fun `a job needs no earlier probe`() {
        val events = run(protocol(access()))
        assertEquals(JobEvent.Completed, events.last())
        assertEquals(1, ipp.jobs.size)
    }

    @Test
    fun `a transfer that fails on the classic interface is a USB problem with the cause kept`() {
        val real = access(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        val unplugged = object : UsbPrinterAccess by real {
            override fun openLegacy(plan: UsbPrinterPlan.Legacy): ByteChannel = object : ByteChannel {
                override fun write(data: ByteArray, offset: Int, length: Int) = throw IOException("device gone")
                override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Int) = 0
                override fun close() {}
            }
        }
        val failed = assertIs<JobEvent.Failed>(run(protocol(unplugged)).last())
        val problem = assertIs<PrintFailure.UsbProblem>(failed.failure)
        assertTrue("device gone" in problem.reason, problem.reason)
        assertEquals("device gone", problem.cause?.cause?.message, "the original exception is kept at the bottom of the chain")
    }

    @Test
    fun `an IPP job whose connection fails is a USB problem too`() {
        val access = access()
        val protocol = protocol(access)
        protocol.probe()
        ipp.behavior.dropAfterBodyBytes = 5 // the "cable" drops mid-request
        val failed = assertIs<JobEvent.Failed>(run(protocol).last())
        assertIs<PrintFailure.UsbProblem>(failed.failure)
    }

    @Test
    fun `failing to claim the interface for a job is reported as a USB problem`() {
        val broken = access().also { it.ippClaimFails = true }
        val protocol = protocol(broken, UsbMode.IPP_USB)
        val failed = assertIs<JobEvent.Failed>(run(protocol).single())
        assertIs<PrintFailure.UsbProblem>(failed.failure)
    }

    @Test
    fun `raw mode sends the document without any PJL`() {
        val protocol = protocol(access(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false)), UsbMode.RAW)
        assertEquals("raw", run(protocol).let { protocol.routeName })
        assertTrue(pjl.allPjlLines.isEmpty(), "no PJL was sent: ${pjl.allPjlLines}")
    }

    @Test
    fun `route name is unknown before the first probe`() {
        assertNull(protocol(access()).routeName)
    }
}
