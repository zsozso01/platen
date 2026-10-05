package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import io.github.zsozso01.platen.core.engine.PdfFile
import io.github.zsozso01.platen.core.layout.PageGeometry
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A PDF on disk, rendered with the platform's `PdfRenderer`. The file itself is also offered for
 * pass-through printing. `PdfRenderer` allows one open page at a time, so every call is serialised.
 */
public class PdfRendererDocument private constructor(
    private val file: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val name: String,
    private val deleteOnClose: Boolean,
) : RenderableDocument {
    override val pageCount: Int = renderer.pageCount
    private val sizes = arrayOfNulls<PageGeometry>(pageCount)

    override val pdf: PdfFile = object : PdfFile {
        override val length: Long get() = file.length()

        override fun open(): InputStream = file.inputStream()
    }

    @Synchronized
    override fun pageGeometry(pageIndex: Int): PageGeometry = sizes[pageIndex] ?: renderer.openPage(pageIndex).use {
        // PdfRenderer reports points and accounts for the page's own /Rotate.
        PageGeometry(it.width.toDouble(), it.height.toDouble())
    }.also { sizes[pageIndex] = it }

    @Synchronized
    override fun drawPage(pageIndex: Int, target: Bitmap, matrix: Matrix, clip: Rect) {
        renderer.openPage(pageIndex).use { it.render(target, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) }
    }

    @Synchronized
    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
        if (deleteOnClose) file.delete()
    }

    public companion object {
        /** Opens [file]. Takes ownership of it (deletes it on close) when [deleteOnClose] is set. */
        @Throws(DocumentOpenException::class)
        public fun open(file: File, name: String, deleteOnClose: Boolean = false): PdfRendererDocument {
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: IOException) {
                throw DocumentOpenException(DocumentOpenException.Reason.UNREADABLE, "Cannot read $name", e)
            }
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: SecurityException) {
                descriptor.close()
                throw DocumentOpenException(DocumentOpenException.Reason.PASSWORD_PROTECTED, "$name is password protected", e)
            } catch (e: IOException) {
                descriptor.close()
                throw DocumentOpenException(DocumentOpenException.Reason.CORRUPT, "$name is not a valid PDF", e)
            } catch (e: RuntimeException) {
                descriptor.close()
                throw DocumentOpenException(DocumentOpenException.Reason.CORRUPT, "$name is not a valid PDF", e)
            }
            if (renderer.pageCount == 0) {
                renderer.close()
                descriptor.close()
                throw DocumentOpenException(DocumentOpenException.Reason.CORRUPT, "$name has no pages")
            }
            return PdfRendererDocument(file, descriptor, renderer, name, deleteOnClose)
        }
    }
}
