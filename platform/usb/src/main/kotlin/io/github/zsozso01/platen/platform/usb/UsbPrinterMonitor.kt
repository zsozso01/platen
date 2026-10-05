package io.github.zsozso01.platen.platform.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.zsozso01.platen.transport.usb.HostUsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.UsbDeviceInfo
import io.github.zsozso01.platen.transport.usb.UsbInterfaceInfo
import io.github.zsozso01.platen.transport.usb.UsbPrinterAccess
import io.github.zsozso01.platen.transport.usb.UsbPrinterPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume

/** A USB device that looks like a printer and is plugged in right now. */
public data class AttachedUsbPrinter(
    /** Android's handle for this plug-in (`/dev/bus/usb/001/004`); changes whenever the cable is replugged. */
    val deviceName: String,
    val info: UsbDeviceInfo,
    val hasPermission: Boolean,
) {
    /** Whether this plug-in is the printer identified by [vendorId], [productId] and, if both are known, [serialNumber]. */
    public fun matches(vendorId: Int, productId: Int, serialNumber: String?): Boolean =
        info.vendorId == vendorId && info.productId == productId &&
            (serialNumber == null || info.serialNumber == null || serialNumber == info.serialNumber)

    /** True if a plan other than "nothing" exists, i.e. Platen can drive it. */
    public val usable: Boolean
        get() = io.github.zsozso01.platen.transport.usb.UsbInterfacePlanner.best(info) != null
}

/**
 * Keeps track of the printers on the USB port, asks for permission to use them and opens them.
 *
 * Android grants USB access per device and only until it is unplugged; plugging in again asks again
 * (unless the user ticked "use by default" for the attach notification, which is then granted automatically).
 */
public class UsbPrinterMonitor(context: Context) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _attached = MutableStateFlow<List<AttachedUsbPrinter>>(emptyList())

    /** The printers plugged in now. Call [start] to keep it current. */
    public val attached: StateFlow<List<AttachedUsbPrinter>> = _attached.asStateFlow()

    private val open = CopyOnWriteArrayList<Pair<String, HostUsbPrinterAccess>>()
    private var started = false

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                val gone = deviceOf(intent)?.deviceName
                if (gone != null) open.filter { it.first == gone }.forEach { it.second.markDetached() }
            }
            refresh()
        }
    }

    /** Starts listening for plug and unplug. Safe to call again. */
    @Synchronized
    public fun start() {
        if (!started) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(app, changes, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            started = true
        }
        refresh()
    }

    @Synchronized
    public fun stop() {
        if (started) runCatching { app.unregisterReceiver(changes) }
        started = false
    }

    /** Re-reads the list of attached printers. */
    public fun refresh() {
        _attached.value = usb.deviceList.values
            .map { device -> AttachedUsbPrinter(device.deviceName, device.toInfo(), usb.hasPermission(device)) }
            .filter { it.info.looksLikePrinter }
            .sortedBy { it.deviceName }
    }

    /**
     * Asks the user to let Platen use [printer]. Returns whether it was granted. Needs the app to be in the
     * foreground: Android shows a system dialog.
     */
    public suspend fun requestPermission(printer: AttachedUsbPrinter): Boolean {
        val device = usb.deviceList[printer.deviceName] ?: return false
        if (usb.hasPermission(device)) return true
        val granted = suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (deviceOf(intent)?.deviceName != device.deviceName) return
                    runCatching { app.unregisterReceiver(this) }
                    if (continuation.isActive) continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
            // The system fills in the device and the answer, so the PendingIntent must be mutable; it must also
            // be explicit (Android 14 refuses mutable implicit ones), hence the package.
            val intent = Intent(ACTION_PERMISSION).setPackage(app.packageName)
            val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, pending)
        }
        refresh()
        return granted
    }

    /**
     * Opens [deviceName] for a probe or a job. The caller closes the access; closing it hands the device
     * back. Throws [IOException] with a message fit for the user when that is not possible.
     */
    @Throws(IOException::class)
    public fun open(deviceName: String): UsbPrinterAccess {
        val device = usb.deviceList[deviceName] ?: throw IOException("The printer is not connected")
        if (!usb.hasPermission(device)) throw IOException("Platen has not been allowed to use this USB printer")
        val connection = usb.openDevice(device) ?: throw IOException("Android could not open the USB printer")
        val access = HostUsbPrinterAccess(device.toInfo(), AndroidUsbHost(device, connection))
        open += deviceName to access
        return object : UsbPrinterAccess by access {
            override fun close() {
                open.removeAll { it.second === access }
                access.close()
            }
        }
    }

    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private companion object {
        const val ACTION_PERMISSION = "io.github.zsozso01.platen.USB_PERMISSION"
    }
}
