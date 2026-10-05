package io.github.zsozso01.platen.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zsozso01.platen.AppContainer
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.data.SavedPrinter
import io.github.zsozso01.platen.data.SavedUsb
import io.github.zsozso01.platen.job.RefCountedDocument
import io.github.zsozso01.platen.platform.usb.AttachedUsbPrinter
import io.github.zsozso01.platen.platform.render.DocumentOpenException
import io.github.zsozso01.platen.route.ipp.AddressProbeException
import io.github.zsozso01.platen.route.ipp.IppAddressProbe
import io.github.zsozso01.platen.transport.network.DiscoveredPrinter
import io.github.zsozso01.platen.transport.network.PrinterAddress
import io.github.zsozso01.platen.transport.network.TcpConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live status of a saved printer, shown on the home screen. */
sealed interface PrinterStatus {
    data object Checking : PrinterStatus
    data class Online(val state: PrinterState, val issues: List<PrinterIssue>) : PrinterStatus
    data object Unreachable : PrinterStatus

    /** A USB printer that is not plugged in. */
    data object Disconnected : PrinterStatus

    /** A USB printer that is plugged in but not yet allowed: Android asks the user once per plug-in. */
    data object NeedsPermission : PrinterStatus
}

/** The outcome of adding a USB printer. */
sealed interface AddUsbResult {
    data class Added(val printer: SavedPrinter) : AddUsbResult
    data object PermissionDenied : AddUsbResult
    data object Unsupported : AddUsbResult
    data class Failed(val message: String) : AddUsbResult
}

/** The outcome of trying to add a printer. */
sealed interface AddResult {
    data class Added(val printer: SavedPrinter) : AddResult
    data object InvalidAddress : AddResult
    data class Failed(val reason: AddressProbeException.Reason) : AddResult
}

sealed interface OpenResult {
    data object Opened : OpenResult
    data object NoPrinter : OpenResult
    data class Failed(val reason: DocumentOpenException.Reason) : OpenResult
}

class AppViewModel(private val container: AppContainer) : ViewModel() {
    val printers: StateFlow<List<SavedPrinter>> = container.printerStore.printers
    val job = container.jobManager.state

    private val _statuses = MutableStateFlow<Map<String, PrinterStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PrinterStatus>> = _statuses.asStateFlow()

    private val _session = MutableStateFlow<PrintSession?>(null)
    val session: StateFlow<PrintSession?> = _session.asStateFlow()

    private val _openError = MutableStateFlow<DocumentOpenException.Reason?>(null)
    val openError: StateFlow<DocumentOpenException.Reason?> = _openError.asStateFlow()

    /** The printer used for the next document: the one the user chose last, else the first. */
    private val _selectedPrinterId = MutableStateFlow<String?>(null)
    val selectedPrinterId: StateFlow<String?> = _selectedPrinterId.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredPrinter>>(emptyList())

    /** Printers found by mDNS that are not saved yet. Empty unless [startDiscovery] is running. */
    val discovered: StateFlow<List<DiscoveredPrinter>> = _discovered.asStateFlow()
    private var discoveryJob: Job? = null

    /** USB printers that are plugged in now. */
    val usbPrinters: StateFlow<List<AttachedUsbPrinter>> = container.usbMonitor.attached

    init {
        refreshStatuses()
        // A cable plugged in or pulled out, or a permission granted, changes what the USB printers can do.
        viewModelScope.launch {
            container.usbMonitor.attached.drop(1).collect { refreshStatuses() }
        }
    }

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            container.discovery.discover().collect { found ->
                val saved = printers.value.map { it.hostHeader to it.path }.toSet()
                _discovered.value = found.filterNot { (if (it.port == 631) it.host else "${it.host}:${it.port}") to it.path in saved }
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _discovered.value = emptyList()
    }

    fun selectPrinter(id: String) {
        _selectedPrinterId.value = id
    }

    private fun chosenPrinter(): SavedPrinter? = printers.value.firstOrNull { it.id == _selectedPrinterId.value } ?: printers.value.firstOrNull()

    // --- printers ---------------------------------------------------------------------------------

    fun refreshStatuses() {
        // A USB printer in the middle of a job must not be opened a second time just to look at it.
        val busy = container.jobManager.isBusy
        val list = printers.value.filterNot { it.isUsb && busy }
        _statuses.update { old -> old + list.associate { it.id to PrinterStatus.Checking } }
        viewModelScope.launch {
            list.map { printer ->
                async(Dispatchers.IO) { _statuses.update { it + (printer.id to statusOf(printer)) } }
            }.awaitAll()
        }
    }

    private fun statusOf(printer: SavedPrinter): PrinterStatus {
        if (printer.isUsb) {
            val attached = container.printerRouter.attachedFor(printer) ?: return PrinterStatus.Disconnected
            if (!attached.hasPermission) return PrinterStatus.NeedsPermission
        }
        return runCatching { container.printerRouter.open(printer, connectTimeoutMillis = 3_000).use { it.protocol.probe() } }
            .fold({ PrinterStatus.Online(it.state, it.issues) }, { PrinterStatus.Unreachable })
    }

    suspend fun addPrinter(text: String): AddResult {
        val address = PrinterAddress.parse(text) ?: return AddResult.InvalidAddress
        return withContext(Dispatchers.IO) {
            try {
                val found = IppAddressProbe.probe(address) { TcpConnector(it.host, it.port, connectTimeoutMillis = 4_000) }
                val saved = SavedPrinter(
                    id = "ipp:${address.hostHeader}${found.path}",
                    name = found.probe.makeAndModel?.takeIf { it.isNotBlank() } ?: address.host,
                    host = address.host,
                    port = address.port,
                    path = found.path,
                    makeAndModel = found.probe.makeAndModel,
                )
                container.printerStore.add(saved)
                _selectedPrinterId.value = saved.id
                _statuses.update { it + (saved.id to PrinterStatus.Online(found.probe.state, found.probe.issues)) }
                AddResult.Added(saved)
            } catch (e: AddressProbeException) {
                AddResult.Failed(e.reason)
            }
        }
    }

    /**
     * Adds a USB printer that is plugged in: asks Android for permission, asks the printer what it is, and
     * saves it. Must be called while the app is in the foreground (the permission dialog needs it).
     */
    suspend fun addUsbPrinter(attached: AttachedUsbPrinter): AddUsbResult {
        if (!attached.usable) return AddUsbResult.Unsupported
        if (!attached.hasPermission && !container.usbMonitor.requestPermission(attached)) return AddUsbResult.PermissionDenied
        // Permission makes the serial number readable, so take the device as it is now.
        val now = container.usbMonitor.attached.value.firstOrNull { it.deviceName == attached.deviceName } ?: return AddUsbResult.Failed("The printer was unplugged")
        val info = now.info
        val saved = SavedPrinter(
            id = SavedPrinter.usbId(info.vendorId, info.productId, info.serialNumber),
            name = info.displayName,
            usb = SavedUsb(info.vendorId, info.productId, info.serialNumber),
        )
        return withContext(Dispatchers.IO) {
            try {
                val probe = container.printerRouter.open(saved).use { it.protocol.probe() }
                val named = saved.copy(name = probe.makeAndModel?.takeIf { it.isNotBlank() } ?: saved.name, makeAndModel = probe.makeAndModel)
                container.printerStore.add(named)
                _selectedPrinterId.value = named.id
                _statuses.update { it + (named.id to PrinterStatus.Online(probe.state, probe.issues)) }
                AddUsbResult.Added(named)
            } catch (e: java.io.IOException) {
                container.diagnostics.log("add usb printer failed: ${e.message}")
                AddUsbResult.Failed(e.message ?: "The printer did not answer")
            }
        }
    }

    /** Asks Android to let Platen use a saved USB printer that is plugged in (the card's "Allow" button). */
    fun allowUsb(printer: SavedPrinter) {
        val attached = container.printerRouter.attachedFor(printer) ?: return
        viewModelScope.launch { container.usbMonitor.requestPermission(attached) }
    }

    /** True if a USB printer is plugged in that has not been added yet. Used when the cable is plugged in while the app is closed. */
    fun hasUnsavedUsbPrinter(): Boolean {
        container.usbMonitor.refresh()
        val saved = printers.value.mapNotNull { it.usb }
        return usbPrinters.value.any { a -> a.usable && saved.none { a.matches(it.vendorId, it.productId, it.serialNumber) } }
    }

    /** The text of the diagnostics report, for the user to share. */
    fun diagnosticsReport(context: android.content.Context): String = container.diagnostics.report(context, container.usbMonitor.attached.value)

    fun removePrinter(id: String) {
        container.printerStore.remove(id)
        _statuses.update { it - id }
    }

    // --- documents --------------------------------------------------------------------------------

    fun openDocument(uri: Uri): OpenResult {
        val printer = chosenPrinter()
        if (printer == null) return OpenResult.NoPrinter
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { container.documentOpener.open(uri) } }
            result.onSuccess { doc ->
                _session.value?.close()
                _session.value = PrintSession(container, RefCountedDocument(doc), printers.value, printer)
            }.onFailure { e ->
                _openError.value = (e as? DocumentOpenException)?.reason ?: DocumentOpenException.Reason.UNREADABLE
            }
        }
        return OpenResult.Opened
    }

    fun cancelJob() = container.jobManager.cancel()

    fun confirmReloaded() = container.jobManager.confirmReloaded()

    fun dismissJob() = container.jobManager.dismiss()

    fun clearOpenError() {
        _openError.value = null
    }

    fun closeSession() {
        _session.value?.close()
        _session.value = null
        refreshStatuses()
    }

    override fun onCleared() {
        _session.value?.close()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(container) as T
    }
}
