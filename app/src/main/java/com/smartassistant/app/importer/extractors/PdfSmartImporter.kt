package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.WordBox
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** يجمع كل قطعة نص مع إحداثياتها الحقيقية من الـ PDF */
private class WordCollector : PDFTextStripper() {
    val words = mutableListOf<WordBox>()
    init { setSortByPosition(true) }
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isNotEmpty()) {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (tp in textPositions) {
                x0 = minOf(x0, tp.x); y0 = minOf(y0, tp.y - tp.height)
                x1 = maxOf(x1, tp.x + tp.width); y1 = maxOf(y1, tp.y)
            }
            val t = text.trim()
            if (t.isNotEmpty()) words.add(WordBox(t, getCurrentPageNo(), x0, y0, x1, y1))
        }
        super.writeString(text, textPositions)
    }
}

object PdfSmartImporter {

    private val TOTAL = Regex("[إا]جمالي|المجموع", RegexOption.IGNORE_CASE)
    private val PAGE_FOOTER = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")
    private val CURR_TOKEN = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd|\\$|€|£", RegexOption.IGNORE_CASE)
    private val HDR_KEYS = listOf("اسم", "إسم", "عميل", "رصيد", "مدين", "دائن", "عمل", "كمية", "كميه", "وحدة", "وحده", "صنف", "مخزن")

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val collector = WordCollector()
            collector.setEndPage(minOf(doc.numberOfPages, 300))
            collector.getText(doc)
            var rowNum = 0
            for (line in buildLogicalLines(collector.words)) {
                if (line.isBlank()) continue
                if (line.contains("#") || line.contains("---") || line.contains("===")) continue
                if (PAGE_FOOTER.containsMatchIn(line)) continue
                if (TOTAL.containsMatchIn(line)) continue
                if (shopName != null && line.contains(shopName)) continue
                if (isHeader(line)) continue
                val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.size < 2) continue
                rowNum++
                if (kind == ImportKind.CUSTOMER) parseCustomerLine(tokens, session, rowNum, out)
                else parseProductLine(tokens, session, rowNum, out)
            }
        }
        return out
    }

    /** دمج القطع النصية الواقعة على نفس الارتفاع في سطر منطقي واحد (بترتيب X) */
    private fun buildLogicalLines(words: List<WordBox>): List<String> {
        val out = mutableListOf<String>()
        words.groupBy { it.page }.toSortedMap().forEach { (_, pw) ->
            val sorted = pw.sortedWith(compareBy({ it.centerY }, { it.x0 }))
            val current = mutableListOf<WordBox>()
            var lastY: Float? = null
            fun flush() {
                if (current.isNotEmpty()) {
                    out.add(current.sortedBy { it.x0 }.joinToString(" ") { it.text })
                    current.clear()
                }
            }
            for (w in sorted) {
                val ly = lastY
                if (ly != null && kotlin.math.abs(w.centerY - ly) > maxOf(6f, w.height * 0.8f)) flush()
                current.add(w)
                lastY = w.centerY
            }
            flush()
        }
        return out
    }

    private fun isHeader(line: String): Boolean = HDR_KEYS.count { line.contains(it) } >= 2

    private fun parseCustomerLine(tokens: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        var i = 0
        val currSb = StringBuilder()
        while (i < tokens.size && CURR_TOKEN.containsMatchIn(tokens[i])) {
            if (currSb.isNotEmpty()) currSb.append(' ')
            currSb.append(tokens[i]); i++
        }
        val nums = mutableListOf<Double>()
        while (i < tokens.size && nums.size < 2) {
            val v = NumberParser.parse(tokens[i]).value ?: break
            nums += v; i++
        }
        val name = tokens.drop(i).joinToString(" ").trim()
        if (name.isEmpty() || nums.isEmpty()) { out.ignored++; return }
        val credit = nums.getOrElse(0) { 0.0 }
        val debit = nums.getOrElse(1) { 0.0 }
        val t = ArabicNormalizer.process(name)
        out.rows.add(com.smartassistant.app.data.local.entity.ImportRawRow(
            sessionId = session, pageNumber = 0, rowNumber = rowNum,
            nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
            credit = credit, debit = debit,
            currency = currSb.toString().ifEmpty { null },
            net = debit - credit, status = "VALID", confidenceScore = 95,
            sourceCoordinates = "line$rowNum", issues = null, approved = 1))
        out.totalCredit += credit; out.totalDebit += debit
    }

    private fun parseProductLine(tokens: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        val qty = NumberParser.parse(tokens[0]).value
        if (qty == null) { out.ignored++; return }
        var i = 1
        var unit: String? = null
        if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) { unit = tokens[i]; i++ }
        val whIdx = tokens.indexOfFirst { it.contains("المخزن") }
        val end = if (whIdx > i) whIdx else tokens.size
        val name = tokens.subList(i, end).joinToString(" ").trim()
        if (name.isEmpty()) { out.ignored++; return }
        val t = ArabicNormalizer.process(name)
        out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
    }
}

/** حذف بيانات جلسة سابقة + تنظيف البيانات التالفة غير المرتبطة */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
