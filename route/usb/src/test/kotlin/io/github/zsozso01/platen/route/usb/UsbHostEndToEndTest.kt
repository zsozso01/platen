package io.github.zsozso01.platen.route.usb

import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterJobSettings
import io.github.zsozso01.platen.core.model.DocumentFormat
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.core.model.Sides
import io.github.zsozso01.platen.route.pjl.PjlTiming
import io.github.zsozso01.platen.testing.fakeprinter.FakeIppPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePjlPrinter
import io.github.zsozso01.platen.testing.fakeprinter.FakePrinterProfile
import io.github.zsozso01.platen.testing.usb.SimulatedUsbHost
import io.github.zsozso01.platen.testing.usb.SimulatedUsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.HostUsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.IppUsbConnector
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The whole USB stack above the platform: host rules, claiming, IPP and PJL, against simulated printers. */
class UsbHostEndToEndTest {
    private val ipp = FakeIppPrinter(FakePrinterProfile.laserPdf).also { it.behavior.requiredHost = "localhost" }
    private val pjl = FakePjlPrinter()
    private val spool: File = createTempDirectory("platen-usb-e2e").toFile()
    private val hosts = mutableListOf<SimulatedUsbHost>()
    private val deviceId = "MFG:HP;MDL:HP LaserJet MFP E42540;CMD:PJL,PCL,PCLXL,POSTSCRIPT,PDF;CLS:PRINTER;"

    @AfterTest
    fun tearDown() {
        hosts.forEach(SimulatedUsbHost::close)
        ipp.close()
        pjl.close()
        spool.deleteRecursively()
    }

    private fun protocol(descriptors: UsbDeviceInfo, configure: (SimulatedUsbHost) -> Unit = {}, mode: UsbMode = UsbMode.AUTO): Pair<UsbJobProtocol, SimulatedUsbHost> {
        val host = SimulatedUsbHost(descriptors, ipp, pjl, deviceId).also { hosts += it; configure(it) }
        val access = HostUsbPrinterAccess(descriptors, host, writeTimeoutMillis = 5_000, writeChunkBytes = 256)
        val protocol = UsbJobProtocol(
            access,
            mode = mode,
            pjlTiming = PjlTiming(readSliceMillis = 10, flushMillis = 5, queryFirstByteMillis = 300, statusPollMillis = 40, settleMillis = 30, maxSilentMillis = 400, writeChunkBytes = 256),
            ippPollIntervalMillis = 10,
            busyRetries = 1,
            busyDelayMillis = 10,
            connectorFactory = { IppUsbConnector(it, acquireTimeoutMillis = 2_000, silenceTimeoutMillis = 3_000, sliceMillis = 20) },
        )
        return protocol to host
    }

    private fun submission(payload: ByteArray, settings: PrinterJobSettings = PrinterJobSettings("Doc")): JobSubmission {
        val file = File.createTempFile("job", ".pdf", spool).also { it.writeBytes(payload) }
        return JobSubmission(settings.jobName, DocumentFormat.PDF, file, settings, raster = null)
    }

    private fun run(protocol: UsbJobProtocol, submission: JobSubmission, onEvent: (JobEvent) -> Unit = {}): List<JobEvent> {
        val events = CopyOnWriteArrayList<JobEvent>()
        protocol.submit(submission, CancelToken()) { events += it; onEvent(it) }
        return events
    }

    @Test
    fun `printing over IPP-USB through the host`() {
        val (protocol, host) = protocol(SimulatedUsbPrinterAccess.hpLaserDescriptors())
        val payload = ByteArray(3_000) { (it % 251).toByte() }
        val events = run(protocol, submission(payload, PrinterJobSettings("Doc", copies = 2, sides = Sides.TWO_SIDED_LONG_EDGE)))

        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        assertEquals("ipp-usb", protocol.routeName)
        val job = ipp.jobs.single()
        assertContentEquals(payload, job.document)
        assertEquals("application/pdf", job.documentFormat)
        assertEquals(listOf("localhost"), ipp.requestHeaders.map { it["host"] }.distinct())
        assertTrue(host.releases.keys.containsAll(listOf(0, 1)), "both interfaces were given back: ${host.releases}")
        assertEquals(0, host.softResets.get(), "clean exchanges need no reset")
    }

    @Test
    fun `printing with PJL through the host when the printer has no IPP-USB`() {
        val (protocol, host) = protocol(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        val payload = ByteArray(2_000) { it.toByte() }
        val probe = protocol.probe()
        assertTrue(probe.capabilities.supports(DocumentFormat.PDF))
        assertEquals("Fake LaserJet", probe.makeAndModel)

        val events = run(protocol, submission(payload, PrinterJobSettings("Doc", copies = 2)))
        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        val job = pjl.jobs.single()
        assertContentEquals(payload, job.data)
        assertEquals("2", job.setting("QTY"))
        assertTrue(host.log.count { it == "claim 0" } >= 2, "claimed for the probe and again for the job")
        assertEquals(host.log.count { it == "claim 0" }, host.releases[0]?.get(), "every claim was released")
    }

    @Test
    fun `when the IPP interface is held by someone else the job goes out over PJL on the same device`() {
        val (protocol, _) = protocol(SimulatedUsbPrinterAccess.hpLaserDescriptors(), configure = { it.claimFailsFor = 1 })
        assertEquals("Fake LaserJet", protocol.probe().makeAndModel)
        assertEquals("pjl", protocol.routeName)
        val events = run(protocol, submission(ByteArray(500)))
        assertEquals(JobEvent.Completed, events.last(), "events: $events")
        assertEquals(1, pjl.jobs.size)
        assertTrue(ipp.jobs.isEmpty())
    }

    @Test
    fun `unplugging the printer in the middle of a job is a USB problem`() {
        val (protocol, host) = protocol(SimulatedUsbPrinterAccess.hpLaserDescriptors(ippUsb = false))
        protocol.probe()
        val events = run(protocol, submission(ByteArray(20_000))) { if (it is JobEvent.Sending && it.bytesSent > 0) host.detach() }
        val failed = assertIs<JobEvent.Failed>(events.last(), "events: $events")
        assertIs<PrintFailure.UsbProblem>(failed.failure)
        assertTrue(events.none { it == JobEvent.Completed })
    }
}
