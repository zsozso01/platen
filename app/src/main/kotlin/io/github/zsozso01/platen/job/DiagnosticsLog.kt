package io.github.zsozso01.platen.job

import android.content.Context
import android.os.Build
import io.github.zsozso01.platen.platform.usb.AttachedUsbPrinter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short in-memory record of what Platen did while talking to printers, for the "Share diagnostics"
 * button. It lives only in memory, holds the last few hundred lines, and is never sent anywhere by the app:
 * the user decides whether to share the text. Lines must not contain document names, network addresses or
 * serial numbers; the code that writes them is written with that in mind.
 */
class DiagnosticsLog {
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Synchronized
    fun log(line: String) {
        lines.addLast("${clock.format(Date())} $line")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** The text the user can share: environment, the USB devices that are plugged in, and the recent log. */
    fun report(context: Context, usb: List<AttachedUsbPrinter>): String = buildString {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Platen ${version ?: "?"} diagnostics")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("This text contains no document names, network addresses or serial numbers.")
        appendLine()
        if (usb.isEmpty()) {
            appendLine("USB printers attached: none")
        } else {
            appendLine("USB printers attached:")
            for (printer in usb) {
                val info = printer.info
                appendLine("- %04x:%04x %s permission=%s".format(info.vendorId, info.productId, info.displayName, printer.hasPermission))
                for (iface in info.interfaces) appendLine("    $iface")
            }
        }
        appendLine()
        appendLine("Recent activity:")
        snapshot().forEach(::appendLine)
    }

    private companion object {
        const val MAX_LINES = 400
    }
}
