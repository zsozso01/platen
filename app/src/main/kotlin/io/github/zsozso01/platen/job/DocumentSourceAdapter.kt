package io.github.zsozso01.platen.job

import io.github.zsozso01.platen.core.engine.DocumentSource
import io.github.zsozso01.platen.platform.render.RenderableDocument

/** The engine only needs the [DocumentSource] half; this hides `close()` so the engine can never release a shared document. */
fun RenderableDocument.asSource(): DocumentSource = object : DocumentSource {
    override val name = this@asSource.name
    override val pageCount = this@asSource.pageCount
    override val pdf = this@asSource.pdf

    override fun pageGeometry(pageIndex: Int) = this@asSource.pageGeometry(pageIndex)
}
