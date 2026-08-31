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

    private val SKIP = Regex("اجمالي|المجموع|total|تاريخ الطباع|عنوان المحل|هاتف المحل|اسم الشرك|\#|---|===", RegexOption.IGNORE_CASE)
    private val TOTAL = Regex("اجمالي|المجموع|الإجمالي|الاجمالي|الإجمالي-|اجمالي العمليات", RegexOption.IGNORE_CASE)
    private val PAGE_FOOTER = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")
    private val CURR_TOKEN = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd", RegexOption.IGNORE_CASE)
    private val HDR_KEYS = listOf("اسم", "إسم", "عميل", "رصيد", "مدين", "دائن", "عمل", "كمية", "كميه", "وحدة", "وحده", "صنف", "مخزن")

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 300))
            val text = stripper.getText(doc)
            var rowNum = 0
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                if (SKIP.containsMatchIn(line)) continue
                if (TOTAL.containsMatchIn(line)) continue
                if (PAGE_FOOTER.containsMatchIn(line)) continue
                if (shopName != null && line.contains(shopName)) continue
                if (line.contains("المخزون المتبقي")) continue
                if (HDR_KEYS.count { line.contains(it) } >= 2) continue
                val cells = line.split(Regex("\\s{2,}|\\|")).map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.size < 2) continue
                if (cells.all { it == cells[0] }) continue
                rowNum++
                if (kind == ImportKind.CUSTOMER) parseCustomer(cells, session, rowNum, out)
                else parseProduct(cells, session, rowNum, out)
            }
        }
        return out
    }

    private fun parseCustomer(tokens: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        var i = 0
        val currSb = StringBuilder()
        while (i < tokens.size && CURR_TOKEN.containsMatchIn(tokens[i])) {
            if (currSb.isNotEmpty()) currSb.append(" ")
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
            sourceCoordinates = "line" + rowNum, issues = null, approved = 1))
        out.totalCredit += credit; out.totalDebit += debit
    }

    private fun parseProduct(tokens: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        val qty = NumberParser.parse(tokens[0]).value
        if (qty == null) { out.ignored++; return }
        var i = 1
        var unit: String? = null
        if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) {
            unit = tokens[i].takeIf { it != "-" }; i++
        }
        val whIdx = tokens.indexOfFirst { it.contains("المخزن") || it == "الرئيسي" }
        val end = if (whIdx > i) whIdx else tokens.size
        val name = tokens.subList(i, end).joinToString(" ").trim()
        if (name.isEmpty()) { out.ignored++; return }
        val t = ArabicNormalizer.process(name)
        out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
    }
}
