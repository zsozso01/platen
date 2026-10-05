package io.github.zsozso01.platen.platform.render

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Turns a content `Uri` (from the file picker, a share, or "open with") into a [RenderableDocument].
 * The content is copied into the app's cache first: the source may be a pipe or a short-lived grant, and
 * a seekable local file is what the renderer and a PDF pass-through need. The copy is deleted when the
 * document is closed.
 */
public class DocumentOpener(private val context: Context) {
    @Throws(DocumentOpenException::class)
    public fun open(uri: Uri): RenderableDocument {
        val name = displayName(uri)
        val dir = File(context.cacheDir, "documents").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString())
        try {
            copy(uri, file)
        } catch (e: IOException) {
            file.delete()
            throw DocumentOpenException(DocumentOpenException.Reason.UNREADABLE, "Cannot read $name", e)
        } catch (e: SecurityException) {
            file.delete()
            throw DocumentOpenException(DocumentOpenException.Reason.UNREADABLE, "No permission to read $name", e)
        }
        return try {
            openFile(file, name)
        } catch (e: DocumentOpenException) {
            file.delete()
            throw e
        }
    }

    /** Opens an already-local file, taking ownership of it. */
    @Throws(DocumentOpenException::class)
    public fun openFile(file: File, name: String): RenderableDocument {
        if (looksLikePdf(file)) return PdfRendererDocument.open(file, name, deleteOnClose = true)
        ImageDocument.openOrNull(file, name, deleteOnClose = true)?.let { return it }
        throw DocumentOpenException(
            DocumentOpenException.Reason.UNSUPPORTED_TYPE,
            "$name is not a PDF or an image. Export it as PDF from the app that made it, then print that.",
        )
    }

    private fun looksLikePdf(file: File): Boolean = file.inputStream().use { input ->
        val head = ByteArray(1024)
        val n = input.read(head).coerceAtLeast(0)
        // The PDF header may be preceded by junk, but must appear within the first KiB.
        String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
    }

    private fun copy(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open $uri")
        input.use { source ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw DocumentOpenException(DocumentOpenException.Reason.TOO_LARGE, "The document is larger than ${MAX_BYTES / (1024 * 1024)} MB")
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Document"
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}
