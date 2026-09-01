package com.smartassistant.app.importer.extractors

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.InputStream

/** طبقة قراءة PDF فقط (مطابقة لسلوك محرك التطبيق الأصلي) */
class PdfParserHelper(private val context: Context? = null) {

    data class ImportReport(
        val success: Boolean,
        val pageCount: Int = 0,
        val text: String = "",
        val error: String? = null
    )

    init {
        try { context?.let { PDFBoxResourceLoader.init(it) } } catch (_: Exception) {}
    }

    fun importFile(file: File): ImportReport {
        var doc: PDDocument? = null
        return try {
            doc = PDDocument.load(file)
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 300))
            ImportReport(true, doc.numberOfPages, stripper.getText(doc))
        } catch (e: Exception) {
            ImportReport(false, error = e.message ?: "خطأ غير معروف أثناء الاستيراد")
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }

    fun importUri(uri: Uri): ImportReport {
        val ctx = context ?: return ImportReport(false, error = "Context غير متوفر")
        var doc: PDDocument? = null
        var stream: InputStream? = null
        return try {
            stream = ctx.contentResolver.openInputStream(uri)
            doc = PDDocument.load(stream)
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            ImportReport(true, doc.numberOfPages, stripper.getText(doc))
        } catch (e: Exception) {
            ImportReport(false, error = e.message ?: "خطأ غير معروف أثناء الاستيراد")
        } finally {
            try { doc?.close() } catch (_: Exception) {}
            try { stream?.close() } catch (_: Exception) {}
        }
    }
}
