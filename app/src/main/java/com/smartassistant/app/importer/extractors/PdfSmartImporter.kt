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
 * محرك استيراد PDF مبني على بنية التقريرين الفعليين:
 * عملاء : [عملة] [دائن] [مدين] [الاسم...]
 * أصناف : [كمية] [وحدة] [الصنف...] [المخزن الرئيسي]
 */
object PdfSmartImporter {

    private val CURR_TOKEN = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd|\\$|€|£", RegexOption.IGNORE_CASE)
    private val FOOTER = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")
    private val WH_TOKENS = setOf("المخزن", "الرئيسي", "الفرع")

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            val text = stripper.getText(doc)
            var rowNum = 0
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                // أسطر تُستبعد: عنوان / فاصل / إجمالي / تذييل صفحة
                if (line.contains("#") || line.contains("---") || line.contains("===")) continue
                if (line.contains("اجمالي") || line.contains("الإجمالي") || line.contains("الاجمالي")) continue
                if (FOOTER.containsMatchIn(line)) continue
                if (kind == ImportKind.CUSTOMER) {
                    // رأس جدول العملاء
                    if (line.contains("العملة") && line.contains("مدين")) continue
                } else {
                    // عنوان ورأس جدول المخزون
                    if (line.contains("المتبقي")) continue
                    if (line.contains("الكمية") && line.contains("الصنف")) continue
                }
                val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (tokens.size < 3) continue
                rowNum++
                if (kind == ImportKind.CUSTOMER) parseCustomerLine(tokens, session, rowNum, out)
                else parseProductLine(tokens, session, rowNum, out)
            }
        }
        return out
    }

    /** عملاء: [ريال يمني] [دائن] [مدين] [الاسم...] */
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
        val credit = nums.getOrElse(0) { 0.0 }   // دائن = له
        val debit = nums.getOrElse(1) { 0.0 }    // مدين = عليه
        val t = ArabicNormalizer.process(name)
        out.rows.add(ImportRawRow(
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
    private fun parseProductLine(tokens: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        val qty = NumberParser.parse(tokens[0]).value ?: run { out.ignored++; return }
        var i = 1
        var unit: String? = null
        if (i < tokens.size && NumberParser.parse(tokens[i]).value == null) {
            unit = tokens[i].takeIf { it != "-" }
            i++
        }
        var end = tokens.size
        while (end > i && tokens[end - 1] in WH_TOKENS) end--
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
