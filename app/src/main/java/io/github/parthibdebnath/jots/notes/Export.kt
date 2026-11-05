package io.github.parthibdebnath.jots.notes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import io.github.parthibdebnath.jots.editor.EditorView
import io.github.parthibdebnath.jots.editor.StrokeEntry
import io.github.parthibdebnath.jots.editor.Viewport
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Renders notes to PDF or PNG for sharing. Safe to call off the main thread. */
object Export {

    /** A4 at 72 dpi; each page covers a slice of the note with the page's proportions. */
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 24f

    fun pdf(context: Context, meta: NoteMeta, strokes: List<StrokeEntry>): File {
        val contentW = PAGE_W - 2 * MARGIN
        val s = contentW / Viewport.DOC_WIDTH
        val sliceH = (PAGE_H - 2 * MARGIN) / s
        val bottom = max(contentBottom(strokes), 1f)
        val pages = max(1, ceil(bottom / sliceH).toInt())
        val doc = PdfDocument()
        val renderer = CanvasStrokeRenderer.create()
        try {
            for (p in 0 until pages) {
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, p + 1).create())
                val c = page.canvas
                if (meta.darkPaper) c.drawColor(EditorView.PAPER_DARK)
                val top = p * sliceH
                val m = Matrix().apply {
                    setTranslate(0f, -top)
                    postScale(s, s)
                    postTranslate(MARGIN, MARGIN)
                }
                c.save()
                c.clipRect(MARGIN, MARGIN, PAGE_W - MARGIN, PAGE_H - MARGIN)
                c.concat(m)
                for (e in strokes) if (e.intersects(0f, top, Viewport.DOC_WIDTH, top + sliceH)) renderer.draw(c, e.stroke, m)
                c.restore()
                doc.finishPage(page)
            }
            val f = outFile(context, meta, "pdf")
            f.outputStream().use { doc.writeTo(it) }
            return f
        } finally {
            doc.close()
        }
    }

    fun png(context: Context, meta: NoteMeta, strokes: List<StrokeEntry>): File {
        val bottom = max(contentBottom(strokes), 200f) + 40f
        // Wide enough to look sharp, but cap the height so very long notes still fit in memory.
        val width = min(1600, (16000 * Viewport.DOC_WIDTH / bottom).toInt()).coerceAtLeast(400)
        val s = width / Viewport.DOC_WIDTH
        val height = ceil(bottom * s).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.drawColor(if (meta.darkPaper) EditorView.PAPER_DARK else EditorView.PAPER_LIGHT)
            if (meta.paper == Paper.RULED) {
                val p = Paint().apply { color = if (meta.darkPaper) 0x33FFFFFF else 0x2A1E3A5F; strokeWidth = max(1f, s) }
                var y = EditorView.LINE_SPACING
                while (y < bottom) { c.drawLine(0f, y * s, width.toFloat(), y * s, p); y += EditorView.LINE_SPACING }
            }
            val m = Matrix().apply { setScale(s, s) }
            c.concat(m)
            val renderer = CanvasStrokeRenderer.create()
            for (e in strokes) renderer.draw(c, e.stroke, m)
            val f = outFile(context, meta, "png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f
        } finally {
            bmp.recycle()
        }
    }

    fun shareIntent(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Share note").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun contentBottom(strokes: List<StrokeEntry>): Float =
        strokes.maxOfOrNull { it.bounds?.yMax ?: 0f } ?: 0f

    private fun outFile(context: Context, meta: NoteMeta, ext: String): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // Only the latest export is kept.
        val safe = meta.displayTitle.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "note" }.take(60)
        return File(dir, "$safe.$ext")
    }
}
