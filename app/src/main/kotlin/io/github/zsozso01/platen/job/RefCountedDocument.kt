package io.github.zsozso01.platen.job

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import io.github.zsozso01.platen.core.engine.PdfFile
import io.github.zsozso01.platen.core.layout.PageGeometry
import io.github.zsozso01.platen.platform.render.RenderableDocument
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lets several owners (the print screen, a running job) share one open document. It really closes when
 * the last owner releases it, so leaving the screen never pulls the document out from under a job.
 */
class RefCountedDocument(private val inner: RenderableDocument) : RenderableDocument {
    private val owners = AtomicInteger(1)

    /** Registers another owner. Returns this, or null if the document has already been closed. */
    fun retain(): RefCountedDocument? {
        while (true) {
            val n = owners.get()
            if (n <= 0) return null
            if (owners.compareAndSet(n, n + 1)) return this
        }
    }

    /** Each owner calls this exactly once when done. */
    override fun close() {
        if (owners.decrementAndGet() == 0) inner.close()
    }

    override val name: String get() = inner.name
    override val pageCount: Int get() = inner.pageCount
    override val pdf: PdfFile? get() = inner.pdf
    override fun pageGeometry(pageIndex: Int): PageGeometry = inner.pageGeometry(pageIndex)
    override fun drawPage(pageIndex: Int, target: Bitmap, matrix: Matrix, clip: Rect) = inner.drawPage(pageIndex, target, matrix, clip)
}
