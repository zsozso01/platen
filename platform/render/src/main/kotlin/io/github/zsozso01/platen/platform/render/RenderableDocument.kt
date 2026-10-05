package io.github.zsozso01.platen.platform.render

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import io.github.zsozso01.platen.core.engine.DocumentSource
import java.io.Closeable

/** A [DocumentSource] that can also draw its pages on Android. */
public interface RenderableDocument : DocumentSource, Closeable {
    /**
     * Draws page [pageIndex] (0-based) into [target]. [matrix] maps the page's point coordinates (1/72 inch,
     * origin top-left) to bitmap pixels and may include rotation. Drawing is restricted to [clip], in bitmap
     * pixels, and never touches pixels outside it. [target] is ARGB_8888 and already filled with the background.
     */
    public fun drawPage(pageIndex: Int, target: Bitmap, matrix: Matrix, clip: Rect)
}

/** Why a document could not be opened. The UI maps these to messages. */
public class DocumentOpenException(public val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    public enum class Reason { UNREADABLE, PASSWORD_PROTECTED, UNSUPPORTED_TYPE, CORRUPT, TOO_LARGE }
}
