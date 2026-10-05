package io.github.zsozso01.platen.platform.render

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Builds small PDFs for tests: each page has a coloured square marker at its top-left and its number as large text. */
object TestDocuments {
    val cacheDir: File get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    /** Page sizes in points; page n gets marker colour [colors] (cycled). */
    fun pdf(name: String, sizes: List<Pair<Int, Int>>, colors: List<Int> = listOf(Color.RED, Color.BLUE, Color.rgb(0, 160, 0))): File {
        val file = File(cacheDir, name)
        val doc = PdfDocument()
        sizes.forEachIndexed { i, (w, h) ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            val c = page.canvas
            c.drawColor(Color.WHITE)
            c.drawRect(0f, 0f, 40f, 40f, Paint().apply { color = colors[i % colors.size] })
            val text = Paint().apply { color = Color.BLACK; textSize = 96f; isAntiAlias = true }
            c.drawText("PAGE ${i + 1}", 60f, h / 2f, text)
            c.drawRect(2f, 2f, w - 2f, h - 2f, Paint().apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 2f })
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file
    }

    val A4 = 595 to 842
    val A4_LANDSCAPE = 842 to 595
}
