package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * محرك مبني على البنية الحقيقية للملفين الناجحين:
 * عملاء: كل سجل = 4 أسطر متتالية [العملة][دائن][مدين][الاسم]
 * أصناف: كل سجل = 4 أسطر متتالية [الكمية][الوحدة][الصنف][المخزن الرئيسي]
 * مع دعم السطر الكامل (كل الحقول في سطر واحد) كحالة احتياطية.
 */
object PdfSmartImporter {

    private val CURRENCY = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|ليرة", RegexOption.IGNORE_CASE)
    private val FOOTER = Regex("^\\d{1,4} \\d{4}-\\d{2}-\\d{2}$")
    private val NOISE = listOf(
        "---", "العملة", "دائن", "مدين", "الاسم", "الإسم",
        "الكمية", "الوحدة", "الصنف", "اسم المخزن",
        "عام#العملاء", "المخزون المتبقي",
        "اجمالي", "الإجمالي", "الاجمالي", "المجموع"
    )

    private fun clean(s: String) = s.replace("|", "").replace(Regex("\\s+"), " ").trim()

    private fun isNoise(line: String): Boolean =
        line.isEmpty() || line.contains("---") || FOOTER.containsMatchIn(line) ||
        NOISE.any { line.contains(it) }

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 500))
            val lines = stripper.getText(doc).lines().map { clean(it) }
            if (kind == ImportKind.CUSTOMER) parseCustomers(lines, session, out)
            else parseProducts(lines, session, out)
        }
        return out
    }

    // ================= العملاء =================
    private fun parseCustomers(lines: List<String>, session: Long, out: ImportEngine.AnalyzeResult) {
        var rowNum = 0
        var curr: String? = null
        val nums = mutableListOf<Double>()
        var state = 0 // 0=عملة 1=دائن 2=مدين 3=اسم

        fun reset() { curr = null; nums.clear(); state = 0 }
        fun emit(name: String) {
            if (name.isEmpty()) { reset(); return }
            val credit = nums.getOrElse(0) { 0.0 }
            val debit = nums.getOrElse(1) { 0.0 }
            rowNum++
            val t = ArabicNormalizer.process(name)
            out.rows.add(ImportRawRow(
                sessionId = session, pageNumber = 0, rowNumber = rowNum,
                nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                credit = credit, debit = debit, currency = curr,
                net = debit - credit, status = "VALID", confidenceScore = 95,
                sourceCoordinates = "row$rowNum", issues = null, approved = 1))
            out.totalCredit += credit
            out.totalDebit += debit
            reset()
        }
        fun emitFull(line: String) {
            val tokens = line.split(" ")
            var i = 0
            val csb = StringBuilder()
            while (i < tokens.size && CURRENCY.containsMatchIn(tokens[i])) {
                if (csb.isNotEmpty()) csb.append(' '); csb.append(tokens[i]); i++
            }
            val n = mutableListOf<Double>()
            while (i < tokens.size && n.size < 2) {
                val v = NumberParser.parse(tokens[i]).value ?: break
                n.add(v); i++
            }
            val name = tokens.drop(i).joinToString(" ").trim()
            curr = csb.toString().ifEmpty { null }
            nums.addAll(n)
            emit(name)
        }

        for (line in lines) {
            if (isNoise(line)) continue
            val num = NumberParser.parse(line).value
            val hasCurr = CURRENCY.containsMatchIn(line)
            val tokensCount = line.split(" ").size
            // سطر كامل: عملة + أرقام + اسم
            if (hasCurr && num == null && line.contains(Regex("\\d")) && tokensCount > 3) { emitFull(line); continue }
            when (state) {
                0 -> if (hasCurr && num == null) { curr = line; state = 1 }
                1 -> if (num != null) { nums.add(num); state = 2 } else reset()
                2 -> if (num != null) { nums.add(num); state = 3 } else reset()
                3 -> emit(line)
            }
        }
    }

    // ================= الأصناف =================
    private fun parseProducts(lines: List<String>, session: Long, out: ImportEngine.AnalyzeResult) {
        var qty = 0.0
        var unit: String? = null
        val nameParts = mutableListOf<String>()
        var state = 0 // 0=كمية 1=وحدة 2=صنف 3=مخزن

        fun emit() {
            val name = nameParts.joinToString(" ").trim()
            if (name.isNotEmpty()) {
                val t = ArabicNormalizer.process(name)
                out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
            }
            qty = 0.0; unit = null; nameParts.clear(); state = 0
        }
        fun emitFull(line: String) {
            val tokens = line.split(" ")
            val q = NumberParser.parse(tokens[0]).value ?: return
            var i = 1
            var u: String? = null
            if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) {
                u = tokens[i].takeIf { it != "-" }; i++
            }
            val nm = mutableListOf<String>()
            while (i < tokens.size) {
                val c = tokens[i]
                if (c.contains("المخزن")) break
                nm.add(c); i++
            }
            val name = nm.joinToString(" ").trim()
            if (name.isNotEmpty()) {
                val t = ArabicNormalizer.process(name)
                out.products.add(Product(nameRaw = t.raw, unit = u) to q)
            }
        }

        for (line in lines) {
            if (isNoise(line)) continue
            val num = NumberParser.parse(line).value
            val tokensCount = line.split(" ").size
            // سطر كامل: كمية + وحدة + صنف (+ مخزن)
            if (state == 0 && num != null && tokensCount > 2) { emitFull(line); continue }
            when (state) {
                0 -> if (num != null) { qty = num; state = 1 }
                1 -> { unit = if (line == "-") null else line; state = 2 }
                2 -> if (line.contains("المخزن")) emit() else { nameParts.add(line); state = 3 }
                3 -> {
                    if (line.contains("المخزن")) emit()
                    else { emit(); if (num != null) { qty = num; state = 1 } }
                }
            }
        }
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
