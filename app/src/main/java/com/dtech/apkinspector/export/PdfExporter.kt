package com.dtech.apkinspector.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.widget.Toast
import java.io.OutputStream

class PdfExporter(private val context: Context) {

    fun exportReport(outputStream: OutputStream, title: String, content: List<String>) {
        val pdfDocument = PdfDocument()
        // A4 standard size: 595 x 842
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        val canvas = page.canvas
        val paint = Paint()
        val textPaint = Paint().apply { textSize = 10f }
        val titlePaint = Paint().apply { textSize = 14f; isFakeBoldText = true }

        var y = 40f
        val x = 20f

        canvas.drawText("APK Inspector Report: $title", x, y, titlePaint)
        y += 30f

        content.forEach { line ->
            // Simple truncation to avoid crash if line is too long
            // In a real app, we would implement text wrapping
            val safeLine = if (line.length > 100) line.take(100) + "..." else line

            canvas.drawText(safeLine, x, y, textPaint)
            y += 15f

            // Simple single-page safety
            if (y > 800) {
                // Stop drawing to prevent off-page drawing
                // MVP limitation: Single page report
                return@forEach
            }
        }

        pdfDocument.finishPage(page)

        try {
            outputStream.use {
                pdfDocument.writeTo(it)
            }
            // Toast.makeText(context, "Report Saved Successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            // Toast.makeText(context, "Error saving report", Toast.LENGTH_SHORT).show()
        } finally {
            pdfDocument.close()
        }
    }
}
