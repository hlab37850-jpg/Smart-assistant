package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser

/**
 * محلل نص تقريري المبني على بنية التقريرين الفعليين:
 * عملاء: [عملة] [دائن] [مدين] [الاسم]  |  أصناف: [كمية] [وحدة] [الصنف] [المخزن الرئيسي]
 * مع دمج تكيفي يعمل سواء كان السطر كاملاً أو كل خلية بسطر مستقل.
 */
object ReportTextParser {

    private val CURR = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd|\\$|€|£", RegexOption.IGNORE_CASE)
    private val FOOTER = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")

    private fun norm(s: String) = s.replace(Regex("[أإآ]"), "ا")

    private fun isSkip(line: String): Boolean {
        if (line.isEmpty()) return true
        if (line.contains("#") || line.contains("---") || line.contains("===")) return true
        if (FOOTER.containsMatchIn(line)) return true
        val n = norm(line)
        if (n.contains("اجمالي") || n.contains("المجموع")) return true
        if (n.contains("المخزون المتبقي")) return true
        if (n.contains("العملة") && n.contains("مدين")) return true
        if (n.contains("الكمية") && n.contains("الصنف")) return true
        return false
    }

    private fun tokensOf(line: String) = line.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun parse(text: String, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        if (kind == ImportKind.CUSTOMER) parseCustomers(text, session, out)
        else parseProducts(text, session, out)
        return out
    }

    private fun parseCustomers(text: String, session: Long, out: ImportEngine.AnalyzeResult) {
        var curr: String? = null
        val nums = mutableListOf<Double>()
        var rowNum = 0
        fun reset() { curr = null; nums.clear() }
        fun emit(name: String) {
            if (name.isEmpty() || nums.isEmpty()) { out.ignored++; reset(); return }
            rowNum++
            val credit = nums.getOrElse(0) { 0.0 }
            val debit = nums.getOrElse(1) { 0.0 }
            val t = ArabicNormalizer.process(name)
            out.rows.add(ImportRawRow(
                sessionId = session, pageNumber = 0, rowNumber = rowNum,
                nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                credit = credit, debit = debit, currency = curr,
                net = debit - credit, status = "VALID", confidenceScore = 95,
                sourceCoordinates = "line$rowNum", issues = null, approved = 1))
            out.totalCredit += credit
            out.totalDebit += debit
            reset()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (isSkip(line)) { reset(); continue }
            val tokens = tokensOf(line)
            val numsInLine = tokens.mapNotNull { NumberParser.parse(it).value }
            val textTokens = tokens.filter { NumberParser.parse(it).value == null && !CURR.containsMatchIn(it) }
            val startsCurr = tokens.isNotEmpty() && CURR.containsMatchIn(tokens[0])
            when {
                startsCurr -> {
                    reset()
                    val csb = StringBuilder()
                    var i = 0
                    while (i < tokens.size && CURR.containsMatchIn(tokens[i])) {
                        if (csb.isNotEmpty()) csb.append(' ')
                        csb.append(tokens[i]); i++
                    }
                    curr = csb.toString()
                    while (i < tokens.size && nums.size < 2) {
                        val v = NumberParser.parse(tokens[i]).value ?: break
                        nums.add(v); i++
                    }
                    val name = tokens.drop(i).joinToString(" ").trim()
                    if (name.isNotEmpty()) emit(name)
                }
                curr != null && numsInLine.isNotEmpty() && textTokens.isEmpty() -> {
                    for (v in numsInLine) if (nums.size < 2) nums.add(v)
                }
                curr != null && textTokens.isNotEmpty() && numsInLine.isEmpty() -> emit(line)
                numsInLine.isNotEmpty() && textTokens.isNotEmpty() -> {
                    rowNum++
                    val credit = numsInLine.getOrElse(0) { 0.0 }
                    val debit = numsInLine.getOrElse(1) { 0.0 }
                    val t = ArabicNormalizer.process(textTokens.joinToString(" "))
                    out.rows.add(ImportRawRow(
                        sessionId = session, pageNumber = 0, rowNumber = rowNum,
                        nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                        credit = credit, debit = debit, currency = null,
                        net = debit - credit, status = "VALID", confidenceScore = 80,
                        sourceCoordinates = "line$rowNum", issues = "no_currency", approved = 1))
                    out.totalCredit += credit
                    out.totalDebit += debit
                    reset()
                }
                else -> out.ignored++
            }
        }
        if (curr != null) out.ignored++
    }

    private fun parseProducts(text: String, session: Long, out: ImportEngine.AnalyzeResult) {
        var qty: Double? = null
        var unit: String? = null
        val nameParts = mutableListOf<String>()
        fun emit() {
            val q = qty
            val name = nameParts.joinToString(" ").trim()
            if (q != null && name.isNotEmpty()) {
                val t = ArabicNormalizer.process(name)
                out.products.add(Product(nameRaw = t.raw, unit = unit) to q)
            } else out.ignored++
            qty = null; unit = null; nameParts.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (isSkip(line)) { if (qty != null) emit(); continue }
            val tokens = tokensOf(line)
            val firstNum = tokens.firstOrNull()?.let { NumberParser.parse(it).value }
            when {
                firstNum != null -> {
                    if (qty != null) emit()
                    qty = firstNum
                    var i = 1
                    if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) {
                        unit = tokens[i].takeIf { it != "-" }; i++
                    }
                    var end = tokens.size
                    while (end > i && (tokens[end - 1] == "المخزن" || tokens[end - 1] == "الرئيسي")) end--
                    if (i < end) nameParts.addAll(tokens.subList(i, end))
                    if (end < tokens.size) emit()
                }
                qty != null && tokens.isNotEmpty() -> {
                    if (line.contains("المخزن")) emit()
                    else if (unit == null && tokens.size == 1) unit = tokens[0].takeIf { it != "-" }
                    else nameParts.addAll(tokens)
                }
                else -> out.ignored++
            }
        }
        if (qty != null) emit()
    }
}
