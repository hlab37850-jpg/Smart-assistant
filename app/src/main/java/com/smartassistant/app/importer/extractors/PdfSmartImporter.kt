package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

object PdfSmartImporter {

    private val TOTAL = Regex("اجمالي|المجموع|الإجمالي|الاجمالي", RegexOption.IGNORE_CASE)
    private val PAGE_FOOTER = Regex("^\d{1,4}\s+\d{4}-\d{2}-\d{2}$")
    private val CURR_TOKEN = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd|\\$|€|£", RegexOption.IGNORE_CASE)
    private val HDR_KEYS = listOf("اسم", "إسم", "عميل", "رصيد", "مدين", "دائن", "عمل", "كمية", "كميه", "وحدة", "وحده", "صنف", "مخزن")

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 300))
            val text = stripper.getText(doc)
            val logical = buildLogicalLines(text, kind)
            var rowNum = 0
            for (line in logical) {
                val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.size < 2) continue
                rowNum++
                if (kind == ImportKind.CUSTOMER) parseCustomerLine(tokens, session, rowNum, out)
                else parseProductLine(tokens, session, rowNum, out)
            }
        }
        return out
    }

    /** يدمج الأسطر المنفصلة (خلية/سطر) إلى أسطر سجلات كاملة */
    private fun buildLogicalLines(text: String, kind: ImportKind): List<String> {
        val out = mutableListOf<String>()
        var buf: StringBuilder? = null
        fun tokensOf(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun numsOf(s: String) = tokensOf(s).mapNotNull { NumberParser.parse(it).value }.size
        fun nameOf(s: String) = tokensOf(s).count { NumberParser.parse(it).value == null && !CURR_TOKEN.containsMatchIn(it) }
        fun isComplete(s: String): Boolean = if (kind == ImportKind.CUSTOMER)
            numsOf(s) >= 2 && nameOf(s) >= 1
        else
            numsOf(s) >= 1 && nameOf(s) >= 1
        fun isStart(s: String): Boolean {
            val t = tokensOf(s); if (t.isEmpty()) return false
            return if (kind == ImportKind.CUSTOMER) CURR_TOKEN.containsMatchIn(t[0])
            else NumberParser.parse(t[0]).value != null
        }
        fun flush() { buf?.let { if (isComplete(it.toString())) out.add(it.toString()) }; buf = null }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.contains("#") || line.contains("---") || line.contains("===")) { flush(); continue }
            if (TOTAL.containsMatchIn(line) || PAGE_FOOTER.containsMatchIn(line)) { flush(); continue }
            if (HDR_KEYS.count { line.contains(it) } >= 2) { flush(); continue }
            if (buf == null) {
                if (isStart(line) && !isComplete(line)) buf = StringBuilder(line)
                else if (isComplete(line)) out.add(line)
                else buf = StringBuilder(line)
            } else {
                if (isStart(line)) { flush(); buf = StringBuilder(line) }
                else {
                    buf!!.append(' ').append(line)
                    if (isComplete(buf.toString())) flush()
                }
            }
        }
        flush()
        return out
    }

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
        val qty = NumberParser.parse(tokens[0]).value ?: run { out.ignored++; return }
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
