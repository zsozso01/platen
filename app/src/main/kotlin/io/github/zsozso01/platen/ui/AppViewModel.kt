package io.github.zsozso01.platen.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.zsozso01.platen.AppContainer
import io.github.zsozso01.platen.core.model.PrinterIssue
import io.github.zsozso01.platen.core.model.PrinterState
import io.github.zsozso01.platen.data.SavedPrinter
import io.github.zsozso01.platen.job.RefCountedDocument
import io.github.zsozso01.platen.platform.render.DocumentOpenException
import io.github.zsozso01.platen.route.ipp.AddressProbeException
import io.github.zsozso01.platen.route.ipp.IppAddressProbe
import io.github.zsozso01.platen.route.ipp.IppEndpoint
import io.github.zsozso01.platen.route.ipp.IppJobProtocol
import io.github.zsozso01.platen.transport.network.PrinterAddress
import io.github.zsozso01.platen.transport.network.TcpConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live status of a saved printer, shown on the home screen. */
sealed interface PrinterStatus {
    data object Checking : PrinterStatus
    data class Online(val state: PrinterState, val issues: List<PrinterIssue>) : PrinterStatus
    data object Unreachable : PrinterStatus
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

    init {
        refreshStatuses()
    }

    fun selectPrinter(id: String) {
        _selectedPrinterId.value = id
    }

    private fun chosenPrinter(): SavedPrinter? = printers.value.firstOrNull { it.id == _selectedPrinterId.value } ?: printers.value.firstOrNull()

    // --- printers ---------------------------------------------------------------------------------

    fun refreshStatuses() {
        val list = printers.value
        _statuses.value = list.associate { it.id to PrinterStatus.Checking }
        viewModelScope.launch {
            list.map { printer ->
                async(Dispatchers.IO) {
                    val status = runCatching {
                        IppJobProtocol(IppEndpoint(TcpConnector(printer.host, printer.port, connectTimeoutMillis = 3_000), printer.hostHeader, printer.path, printer.uri)).probe()
                    }.fold({ PrinterStatus.Online(it.state, it.issues) }, { PrinterStatus.Unreachable })
                    _statuses.update { it + (printer.id to status) }
                }
            }.awaitAll()
        }
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
