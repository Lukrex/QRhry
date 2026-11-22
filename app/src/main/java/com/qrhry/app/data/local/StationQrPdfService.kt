package com.qrhry.app.data.local

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import com.qrhry.app.domain.Game
import com.qrhry.app.qr.StationQrCodeGenerator
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class StationQrPdfService(private val context: Context) {
    fun create(game: Game): File {
        val directory = File(context.cacheDir, "printable_qr").apply { mkdirs() }
        directory.listFiles()?.filter { it.isFile && it.name.startsWith("stations-") && it.extension == "pdf" }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000 }
            ?.forEach { it.delete() }
        val output = File(directory, "stations-${UUID.randomUUID()}.pdf")
        val pageWidth = 595
        val pageHeight = 842
        val margin = 36f
        val columns = 2
        val rows = 3
        val perPage = columns * rows
        val cellWidth = (pageWidth - margin * 2) / columns
        val cellHeight = (pageHeight - margin * 2 - 50f) / rows
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) }
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
        val pageTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val finalDocument = PdfDocument()
        try {
            val pageCount = (game.stations.size + perPage - 1) / perPage
            for (pageIndex in 0 until pageCount) {
                val page = finalDocument.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
                val canvas = page.canvas
                canvas.drawText(game.title, margin, margin + 18f, pageTitlePaint)
                val stations = game.stations.drop(pageIndex * perPage).take(perPage)
                stations.forEachIndexed { offset, station ->
                    val col = offset % columns
                    val row = offset / columns
                    val left = margin + col * cellWidth
                    val top = margin + 48f + row * cellHeight
                    val numberY = top + 20f
                    canvas.drawText(context.getString(com.qrhry.app.R.string.station_number, station.position + 1), left + 10f, numberY, titlePaint)
                    val name = station.title
                    val maxWidth = cellWidth - 20f
                    val qrSize = 148
                    val qrTop = top + 34f
                    val qrLeft = left + (cellWidth - qrSize) / 2
                    // Four modules of white padding are drawn around the generated symbol as a robust quiet zone.
                    val bitmap = StationQrCodeGenerator.createBitmap(station.qrToken, qrSize - 32)
                    canvas.drawBitmap(bitmap, null, Rect(qrLeft.toInt() + 16, qrTop.toInt() + 16, qrLeft.toInt() + qrSize - 16, qrTop.toInt() + qrSize - 16), null)
                    bitmap.recycle()
                    drawWrapped(canvas, name, left + 10f, qrTop + qrSize + 20f, maxWidth, namePaint, maxLines = 2)
                    canvas.drawLine(left + 8f, top + cellHeight - 6f, left + cellWidth - 8f, top + cellHeight - 6f, Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 1f })
                }
                finalDocument.finishPage(page)
            }
            FileOutputStream(output).use(finalDocument::writeTo)
            return output
        } catch (failure: Exception) {
            output.delete()
            throw failure
        } finally {
            finalDocument.close()
        }
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, baseline: Float, width: Float, paint: Paint, maxLines: Int) {
        val words = text.split(Regex("\\s+"))
        var line = ""
        var lineIndex = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > width && line.isNotEmpty()) {
                canvas.drawText(line, x, baseline + lineIndex * (paint.textSize + 4f), paint)
                lineIndex++
                line = word
                if (lineIndex >= maxLines) break
            } else line = candidate
        }
        if (lineIndex < maxLines && line.isNotEmpty()) canvas.drawText(line, x, baseline + lineIndex * (paint.textSize + 4f), paint)
    }
}
