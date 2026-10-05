package io.github.zsozso01.platen.data

import io.github.zsozso01.platen.core.model.Endpoint
import io.github.zsozso01.platen.core.model.Printer
import io.github.zsozso01.platen.core.model.PrinterId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How to recognise a printer on the USB cable. The system name of a USB device changes on every replug, so
 * identity is the vendor and product id, plus the serial number when the device would tell it.
 */
@Serializable
data class SavedUsb(
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String? = null,
    /** A `UsbMode` name. `AUTO` unless the user chose another way of talking to this printer. */
    val mode: String = "AUTO",
)

/**
 * A printer the user added, as stored on disk. Only what is needed to reach it again; capabilities are
 * asked afresh each time. A network printer has [host], [port] and [path]; a USB printer has [usb] instead.
 */
@Serializable
data class SavedPrinter(
    val id: String,
    val name: String,
    val host: String = "",
    val port: Int = 631,
    val path: String = "",
    val makeAndModel: String? = null,
    val usb: SavedUsb? = null,
) {
    val hostHeader: String get() = if (port == 631) host else "$host:$port"
    val uri: String get() = "ipp://$hostHeader$path"
    val isUsb: Boolean get() = usb != null

    fun toPrinter(): Printer = Printer(
        PrinterId(id),
        name,
        makeAndModel,
        endpoints = listOf(usb?.let { Endpoint.Usb(it.vendorId, it.productId, it.serialNumber, systemName = "") } ?: Endpoint.Ipp(uri)),
    )

    companion object {
        /** Stable id of a USB printer: `usb:03f0:d72a` or, with a serial number, `usb:03f0:d72a:SERIAL`. */
        fun usbId(vendorId: Int, productId: Int, serialNumber: String?): String =
            "usb:%04x:%04x".format(vendorId, productId) + (serialNumber?.let { ":$it" } ?: "")
    }
}

@Serializable
private data class PrinterFile(val version: Int = 1, val printers: List<SavedPrinter> = emptyList())

/**
 * The user's printers, kept in one small JSON file. Writes are atomic (temp file then rename) and a file
 * that cannot be read is set aside rather than deleted, so a crash or a bad edit never loses the list.
 */
class PrinterStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())
    val printers: StateFlow<List<SavedPrinter>> = state.asStateFlow()

    @Synchronized
    fun add(printer: SavedPrinter) = update { list -> list.filterNot { it.id == printer.id } + printer }

    @Synchronized
    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    @Synchronized
    fun rename(id: String, name: String) = update { list -> list.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it } }

    private fun update(change: (List<SavedPrinter>) -> List<SavedPrinter>) {
        val next = change(state.value)
        save(next)
        state.value = next
    }

    private fun load(): List<SavedPrinter> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<PrinterFile>(file.readText()).printers
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".unreadable"))
            emptyList()
        }
    }

    private fun save(list: List<SavedPrinter>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(PrinterFile.serializer(), PrinterFile(printers = list)))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Could not save the printer list" }
        }
    }
}
