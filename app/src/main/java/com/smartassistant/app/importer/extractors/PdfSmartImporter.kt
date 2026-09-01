package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.WordBox
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/** يجمع كل قطعة نص مع إحداثياتها الحقيقية (X/Y) من الـ PDF */
private class WordCollector : PDFTextStripper() {
    val words = mutableListOf<WordBox>()
    init { setSortByPosition(true) }
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isNotEmpty()) {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            for (tp in textPositions) {
                if (tp.x < x0) x0 = tp.x
                if (tp.y - tp.height < y0) y0 = tp.y - tp.height
                if (tp.x + tp.width > x1) x1 = tp.x + tp.width
                if (tp.y > y1) y1 = tp.y
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
    private val WH_TOKENS = setOf("المخزن", "الرئيسي", "الفرع")

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
                if (kind == ImportKind.CUSTOMER) {
                    if (line.contains("العملة") && line.contains("مدين")) continue
                    rowNum++
                    parseCustomerLine(line, session, rowNum, out)
                } else {
                    if (line.contains("الكمية") && line.contains("الصنف")) continue
                    if (line.contains("المخزون المتبقي")) continue
                    rowNum++
                    parseProductLine(line, session, rowNum, out)
                }
            }
        }
        return out
    }

    /** دمج الخلايا الواقعة على نفس الارتفاع Y في سطر منطقي واحد بترتيب X */
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

    /** عملاء: [ريال يمني] [دائن] [مدين] [الاسم...] */
    private fun parseCustomerLine(line: String, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) { out.ignored++; return }
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
        if (currSb.isEmpty() && nums.isEmpty()) { out.ignored++; return }
        if (name.isEmpty()) { out.ignored++; return }
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
        out.totalCredit += credit
        out.totalDebit += debit
    }

    /** أصناف: [كمية] [وحدة] [الصنف...] [المخزن الرئيسي] */
    private fun parseProductLine(line: String, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) { out.ignored++; return }
        val qty = NumberParser.parse(tokens[0]).value ?: run { out.ignored++; return }
        var i = 1
        var unit: String? = null
        if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) {
            unit = tokens[i].takeIf { it != "-" }; i++
        }
        var end = tokens.size
        while (end > i && tokens[end - 1] in WH_TOKENS) end--
        val name = tokens.subList(i, end).joinToString(" ").trim()
        if (name.isEmpty()) { out.ignored++; return }
        val t = ArabicNormalizer.process(name)
        out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
    }
}

/** حذف بيانات جلسة سابقة + تنظيف البيانات غير المرتبطة */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
