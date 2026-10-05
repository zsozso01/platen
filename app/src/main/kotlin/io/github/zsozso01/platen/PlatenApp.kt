package io.github.zsozso01.platen

import android.app.Application
import io.github.zsozso01.platen.data.PrinterStore
import io.github.zsozso01.platen.job.DiagnosticsLog
import io.github.zsozso01.platen.job.JobManager
import io.github.zsozso01.platen.job.PrinterRouter
import io.github.zsozso01.platen.platform.discovery.NsdPrinterDiscovery
import io.github.zsozso01.platen.platform.render.DocumentOpener
import io.github.zsozso01.platen.platform.usb.UsbPrinterMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** The app's long-lived objects. Small enough that a plain container beats a DI framework. */
class AppContainer(app: Application) {
    /** Outlives every screen: print jobs run here. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val printerStore = PrinterStore(File(app.filesDir, "printers.json"))
    val documentOpener = DocumentOpener(app)
    val diagnostics = DiagnosticsLog()

    /** Listens for USB printers for as long as the process lives: a job must notice an unplugged cable too. */
    val usbMonitor = UsbPrinterMonitor(app).also { it.start() }
    val printerRouter = PrinterRouter(usbMonitor, diagnostics)
    val jobManager = JobManager(app, applicationScope, printerRouter, diagnostics)
    val discovery = NsdPrinterDiscovery(app)
}

class PlatenApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
