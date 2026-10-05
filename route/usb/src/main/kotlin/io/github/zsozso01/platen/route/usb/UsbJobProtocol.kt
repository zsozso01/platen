package io.github.zsozso01.platen.route.usb

import io.github.zsozso01.platen.core.engine.CancelToken
import io.github.zsozso01.platen.core.engine.JobProtocol
import io.github.zsozso01.platen.core.engine.JobSubmission
import io.github.zsozso01.platen.core.engine.PrinterProbe
import io.github.zsozso01.platen.core.model.JobEvent
import io.github.zsozso01.platen.core.model.PrintFailure
import io.github.zsozso01.platen.protocol.ipp.IppHttpException
import io.github.zsozso01.platen.route.ipp.IppEndpoint
import io.github.zsozso01.platen.route.ipp.IppJobProtocol
import io.github.zsozso01.platen.route.pjl.PjlJobProtocol
import io.github.zsozso01.platen.route.pjl.PjlTiming
import io.github.zsozso01.platen.transport.usb.IppUsbConnector
import io.github.zsozso01.platen.transport.usb.UsbInterfacePlanner
import io.github.zsozso01.platen.transport.usb.UsbIppPipe
import io.github.zsozso01.platen.transport.usb.UsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.UsbPrinterPlan
import java.io.IOException

/** How Platen talks to a USB printer. [AUTO] is right unless a printer misbehaves; the others are for troubleshooting. */
public enum class UsbMode {
    /** IPP over USB if the printer offers it and answers, otherwise the classic interface with PJL. */
    AUTO,

    /** Only IPP over USB. */
    IPP_USB,

    /** Only the classic printer interface, with PJL. */
    PJL,

    /** The classic printer interface and the bare document: no PJL, nothing asked, nothing tracked. */
    RAW,
}

/**
 * The job protocol of a USB printer: picks between IPP over USB and the classic interface with PJL, in that
 * order, and hands the work to [IppJobProtocol] or [PjlJobProtocol]. A printer that offers both is driven
 * over IPP, which describes the printer fully and reports on jobs properly, and falls back to PJL when
 * the IPP interfaces cannot be claimed or never answer.
 *
 * Interfaces are claimed for the length of one probe or one job and released afterwards.
 */
public class UsbJobProtocol(
    private val access: UsbPrinterAccess,
    private val mode: UsbMode = UsbMode.AUTO,
    private val pjlTiming: PjlTiming = PjlTiming(),
    private val ippPollIntervalMillis: Long = 1_000,
    /** A printer that is still starting up answers 503 for up to about 20 seconds. */
    private val busyRetries: Int = 6,
    private val busyDelayMillis: Long = 3_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val connectorFactory: (List<UsbIppPipe>) -> IppUsbConnector = { IppUsbConnector(it) },
    /** Receives short lines about what was tried, for the diagnostics export. */
    private val trace: (String) -> Unit = {},
) : JobProtocol {
    override val id: String = "usb"

    @Volatile
    private var chosen: UsbPrinterPlan? = null

    /** What the last [probe] settled on, for display and diagnostics: `ipp-usb`, `pjl` or `raw`. */
    public val routeName: String?
        get() = when (chosen) {
            is UsbPrinterPlan.IppUsb -> "ipp-usb"
            is UsbPrinterPlan.Legacy -> if (mode == UsbMode.RAW) "raw" else "pjl"
            null -> null
        }

    private val plans: List<UsbPrinterPlan>
        get() = UsbInterfacePlanner.plans(access.device).filter {
            when (mode) {
                UsbMode.AUTO -> true
                UsbMode.IPP_USB -> it is UsbPrinterPlan.IppUsb
                UsbMode.PJL, UsbMode.RAW -> it is UsbPrinterPlan.Legacy
            }
        }

    private val legacyDeviceId = HashMap<Int, String?>()

    @Throws(IOException::class)
    override fun probe(): PrinterProbe {
        val candidates = plans
        if (candidates.isEmpty()) throw IOException(noInterfaceMessage())
        val failures = mutableListOf<String>()
        var lastCause: IOException? = null
        for (plan in candidates) {
            try {
                val result = when (plan) {
                    is UsbPrinterPlan.IppUsb -> retryWhileBusy { withIpp(plan) { it.probe() } }
                    is UsbPrinterPlan.Legacy -> pjl(plan).probe()
                }
                chosen = plan
                trace("usb: using ${describe(plan)}")
                return result
            } catch (e: IOException) {
                trace("usb: ${describe(plan)} failed: ${e.message}")
                failures += "${describe(plan)}: ${e.message ?: e.javaClass.simpleName}"
                lastCause = e
            }
        }
        throw IOException(failures.joinToString("; "), lastCause)
    }

    override fun submit(submission: JobSubmission, cancel: CancelToken, listener: (JobEvent) -> Unit) {
        val plan = chosen ?: run {
            if (plans.isEmpty()) {
                listener(JobEvent.Failed(PrintFailure.UsbProblem(noInterfaceMessage())))
                return
            }
            try {
                probe()
            } catch (e: IOException) {
                listener(JobEvent.Failed(PrintFailure.UsbProblem("The printer did not answer over USB: ${e.message}", e)))
                return
            }
            chosen ?: return
        }
        val forUsb = usbFailures(listener)
        try {
            when (plan) {
                is UsbPrinterPlan.IppUsb -> withIpp(plan) { it.submit(submission, cancel, forUsb) }
                is UsbPrinterPlan.Legacy -> pjl(plan).submit(submission, cancel, forUsb)
            }
        } catch (e: IOException) {
            // Claiming the interfaces failed before anything was sent.
            listener(JobEvent.Failed(PrintFailure.UsbProblem(usbMessage(e), e)))
        }
    }

    // --- the two routes -----------------------------------------------------------------------------

    private fun <T> withIpp(plan: UsbPrinterPlan.IppUsb, block: (IppJobProtocol) -> T): T {
        val connector = connectorFactory(access.openIppPipes(plan))
        try {
            val endpoint = IppEndpoint(
                connector = connector,
                hostHeader = "localhost",
                path = "/ipp/print",
                printerUri = "ipp://localhost/ipp/print",
                persistentConnection = true,
            )
            return block(IppJobProtocol(endpoint, pollIntervalMillis = ippPollIntervalMillis))
        } finally {
            runCatching { connector.close() }
        }
    }

    private fun pjl(plan: UsbPrinterPlan.Legacy) = PjlJobProtocol(
        open = { access.openLegacy(plan) },
        deviceId = { deviceIdOf(plan) },
        timing = pjlTiming,
        wrapInPjl = mode != UsbMode.RAW,
        ioFailure = { PrintFailure.UsbProblem(usbMessage(it), it) },
        trace = trace,
    )

    private fun deviceIdOf(plan: UsbPrinterPlan.Legacy): String? = synchronized(legacyDeviceId) {
        legacyDeviceId.getOrPut(plan.iface.interfaceNumber) { access.readDeviceId(plan.iface) }
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private fun <T> retryWhileBusy(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IppHttpException) {
                if (e.status != 503 || attempt >= busyRetries) throw e
                attempt++
                trace("usb: the printer is busy (503), try $attempt of $busyRetries")
                sleeper(busyDelayMillis)
            }
        }
    }

    /** A failure to talk to the printer is, on this transport, a USB problem; the UI can say what to check. */
    private fun usbFailures(listener: (JobEvent) -> Unit): (JobEvent) -> Unit = { event ->
        val failure = (event as? JobEvent.Failed)?.failure
        listener(
            if (failure is PrintFailure.Unreachable) JobEvent.Failed(PrintFailure.UsbProblem(usbMessage(failure.cause), failure.cause)) else event,
        )
    }

    private fun usbMessage(cause: Throwable?): String =
        "The USB connection to the printer failed" + (cause?.message?.let { ": $it" } ?: "")

    private fun describe(plan: UsbPrinterPlan): String = when (plan) {
        is UsbPrinterPlan.IppUsb -> "IPP over USB (${plan.interfaces.size} interfaces)"
        is UsbPrinterPlan.Legacy -> if (mode == UsbMode.RAW) "raw printer interface ${plan.iface.interfaceNumber}" else "PJL on printer interface ${plan.iface.interfaceNumber}"
    }

    private fun noInterfaceMessage(): String = when (mode) {
        UsbMode.AUTO -> "This USB device has no printer interface that Platen can use"
        UsbMode.IPP_USB -> "This printer does not offer IPP over USB"
        UsbMode.PJL, UsbMode.RAW -> "This printer has no classic USB printer interface"
    }
}
