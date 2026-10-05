package io.github.zsozso01.platen.job

import io.github.zsozso01.platen.core.engine.JobProtocol
import io.github.zsozso01.platen.data.SavedPrinter
import io.github.zsozso01.platen.platform.usb.AttachedUsbPrinter
import io.github.zsozso01.platen.platform.usb.UsbPrinterMonitor
import io.github.zsozso01.platen.route.ipp.IppEndpoint
import io.github.zsozso01.platen.route.ipp.IppJobProtocol
import io.github.zsozso01.platen.route.usb.UsbJobProtocol
import io.github.zsozso01.platen.route.usb.UsbMode
import io.github.zsozso01.platen.transport.network.TcpConnector
import java.io.Closeable
import java.io.IOException

/** A way to talk to one printer for one probe or job. Closing it releases whatever it holds (a USB device). */
class PrinterRoute(val protocol: JobProtocol, private val onClose: () -> Unit = {}) : Closeable {
    override fun close() = onClose()
}

/** Turns a saved printer into a [JobProtocol]: IPP over the network, or the USB stack for a cable. */
class PrinterRouter(private val usb: UsbPrinterMonitor, private val diagnostics: DiagnosticsLog) {
    /** The plug-in that is this printer right now, if it is plugged in. */
    fun attachedFor(printer: SavedPrinter): AttachedUsbPrinter? {
        val saved = printer.usb ?: return null
        return usb.attached.value.firstOrNull { it.matches(saved.vendorId, saved.productId, saved.serialNumber) }
    }

    /** Opens the printer without asking the user for anything. Throws [IOException] if it cannot be reached. */
    @Throws(IOException::class)
    fun open(printer: SavedPrinter, connectTimeoutMillis: Int = 4_000): PrinterRoute {
        val saved = printer.usb
        if (saved == null) {
            val endpoint = IppEndpoint(TcpConnector(printer.host, printer.port, connectTimeoutMillis = connectTimeoutMillis), printer.hostHeader, printer.path, printer.uri)
            return PrinterRoute(IppJobProtocol(endpoint, pollIntervalMillis = 1_000))
        }
        val attached = attachedFor(printer) ?: throw IOException("The printer is not connected")
        val access = usb.open(attached.deviceName)
        val mode = runCatching { UsbMode.valueOf(saved.mode) }.getOrDefault(UsbMode.AUTO)
        return PrinterRoute(UsbJobProtocol(access, mode = mode, trace = diagnostics::log), access::close)
    }

    /** As [open], but shows Android's permission dialog first if the printer is plugged in and not yet allowed. */
    @Throws(IOException::class)
    suspend fun openAllowingPrompt(printer: SavedPrinter, connectTimeoutMillis: Int = 4_000): PrinterRoute {
        if (printer.isUsb) {
            val attached = attachedFor(printer) ?: throw IOException("The printer is not connected")
            if (!attached.hasPermission && !usb.requestPermission(attached)) throw IOException("USB access was not allowed")
        }
        return open(printer, connectTimeoutMillis)
    }
}
