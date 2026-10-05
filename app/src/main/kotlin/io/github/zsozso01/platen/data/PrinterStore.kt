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

/** A printer the user added, as stored on disk. Only what is needed to reach it again; capabilities are asked afresh each time. */
@Serializable
data class SavedPrinter(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val path: String,
    val makeAndModel: String? = null,
) {
    val hostHeader: String get() = if (port == 631) host else "$host:$port"
    val uri: String get() = "ipp://$hostHeader$path"

    fun toPrinter(): Printer = Printer(PrinterId(id), name, makeAndModel, endpoints = listOf(Endpoint.Ipp(uri)))
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
