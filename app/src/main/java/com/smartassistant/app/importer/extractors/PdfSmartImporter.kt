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

/** خلية جدول: نص + نطاق X (تُبنى من الإحداثيات لا من المسافات) */
data class CellBox(val text: String, val x0: Float, val x1: Float, val centerY: Float) {
    val centerX: Float get() = (x0 + x1) / 2f
}

/** يجمع كل كلمة/خلية مع إحداثياتها من PDF */
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

/**
 * مستورد PDF الذكي: استخراج بالإحداثيات + كشف رؤوس الأعمدة + RTL.
 * القاعدة: لا تخمين — بدون رؤوس أعمدة تُعلَّم السجلات WARNING للمراجعة.
 */
object PdfSmartImporter {

    private val SKIP = Regex("اجمالي|المجموع|total|صفحة|page|تاريخ الطباع|عنوان المحل|هاتف المحل|اسم الشرك", RegexOption.IGNORE_CASE)
    private val TOTAL = Regex("(ال)?اجمالي|المجموع|الإجمالي", RegexOption.IGNORE_CASE)
    private val CURR = Regex("ريال|ريالات|دولار|دينار|درهم|يمني|سعودي|usd|\\$|€|£|جنيه", RegexOption.IGNORE_CASE)
    private val HDR_KEYS = listOf("اسم", "عميل", "رصيد", "له", "عليه", "مدين", "دائن", "عمله", "كود")

    fun extractWords(file: File): List<WordBox> {
        PDDocument.load(file).use { doc ->
            val c = WordCollector()
            c.setEndPage(minOf(doc.numberOfPages, 200))
            c.getText(doc)
            return c.words
        }
    }

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val words = extractWords(file).filter { shopName == null || !it.text.contains(shopName) }
        if (words.isEmpty()) throw IllegalStateException("لا يوجد نص قابل للاستخراج في الملف.")
        val out = ImportEngine.AnalyzeResult()
        var rowNum = 0
        var roles: Map<String, FloatRange>? = null
        words.groupBy { it.page }.toSortedMap().forEach { (page, pw) ->
            for (row in groupRows(pw)) {
                val cells = splitCells(row)
                if (cells.isEmpty()) continue
                val joined = cells.joinToString(" ").trim()
                if (joined.isBlank() || SKIP.containsMatchIn(joined)) { out.ignored++; continue }
                if (TOTAL.containsMatchIn(joined)) { out.ignored++; continue }
                if (isHeader(joined)) { roles = buildRoles(cells); continue }
                rowNum++
                buildRecord(cells, page, rowNum, roles, kind, session, out)
            }
        }
        return out
    }

    private fun groupRows(words: List<WordBox>): List<List<WordBox>> {
        val sorted = words.sortedWith(compareBy({ it.centerY }, { it.x0 }))
        val rows = mutableListOf<MutableList<WordBox>>()
        for (w in sorted) {
            val last = rows.lastOrNull()
            if (last != null && kotlin.math.abs(w.centerY - last[0].centerY) <= maxOf(4f, w.height * 0.6f)) last.add(w)
            else rows.add(mutableListOf(w))
        }
        return rows
    }

    private fun splitCells(row: List<WordBox>): List<CellBox> {
        val s = row.sortedBy { it.x0 }
        if (s.isEmpty()) return emptyList()
        val gaps = mutableListOf<Float>()
        for (i in 1 until s.size) gaps.add(s[i].x0 - s[i - 1].x1)
        val med = if (gaps.isEmpty()) 0f else gaps.sorted()[gaps.size / 2]
        val thr = maxOf(med * 2.5f + 1f, 6f)
        val cells = mutableListOf<CellBox>()
        var text = StringBuilder(s[0].text); var x0 = s[0].x0; var x1 = s[0].x1; val cy = s[0].centerY
        for (i in 1 until s.size) {
            val g = s[i].x0 - s[i - 1].x1
            if (g > thr) {
                cells.add(CellBox(text.toString().trim(), x0, x1, cy))
                text = StringBuilder(s[i].text); x0 = s[i].x0; x1 = s[i].x1
            } else {
                text.append(' ').append(s[i].text); x1 = s[i].x1
            }
        }
        cells.add(CellBox(text.toString().trim(), x0, x1, cy))
        return cells.filter { it.text.isNotEmpty() }
    }

    private fun isHeader(joined: String): Boolean =
        HDR_KEYS.count { joined.contains(it) } >= 2

    private fun roleOf(text: String): String? = when {
        text.contains("رصيد") -> "balance"
        text.contains(Regex("مدين|عليه")) -> "debit"
        text.contains(Regex("دائن|(^|\\s)له")) -> "credit"
        text.contains("عمله") -> "currency"
        text.contains("كود") -> "code"
        text.contains(Regex("اسم|عميل")) -> "name"
        else -> null
    }

    private fun buildRoles(cells: List<CellBox>): Map<String, FloatRange> {
        val m = mutableMapOf<String, FloatRange>()
        cells.forEach { c -> roleOf(c.text)?.let { r -> m[r] = FloatRange(c.x0 - 12f, c.x1 + 12f) } }
        return m
    }

    private fun buildRecord(
        cells: List<CellBox>, page: Int, rowNum: Int,
        roles: Map<String, FloatRange>?, kind: ImportKind,
        session: Long, out: ImportEngine.AnalyzeResult
    ) {
        val nums = cells.mapNotNull { c -> NumberParser.parse(c.text).value?.let { c to it } }
        val texts = cells.filter { NumberParser.parse(it.text).value == null }

        if (kind == ImportKind.PRODUCT) {
            val name = texts.maxByOrNull { it.x0 }?.text ?: return
            val qty = nums.maxByOrNull { it.first.x0 }?.second ?: 0.0
            out.products.add(Product(nameRaw = ArabicNormalizer.process(name).raw) to qty)
            return
        }

        var name: String? = null; var currency: String? = null
        var credit = 0.0; var debit = 0.0
        var confidence = 95; val issues = mutableListOf<String>()

        if (roles != null) {
            for (c in cells) {
                val num = NumberParser.parse(c.text).value
                val role = roles.entries
                    .filter { it.value.contains(c.centerX) }
                    .minByOrNull { kotlin.math.abs((it.value.start + it.value.endInclusive) / 2 - c.centerX) }?.key
                when {
                    role == "name" -> name = ((name?.plus(" ") ?: "") + c.text)
                    role == "credit" -> credit = num ?: credit
                    role == "debit" -> debit = num ?: debit
                    role == "balance" -> { val v = num ?: 0.0; if (v >= 0) debit = v else credit = -v }
                    role == "currency" -> currency = c.text
                    role == "code" -> {}
                    num == null && CURR.containsMatchIn(c.text) -> currency = c.text
                    num == null && name == null -> name = c.text
                    num != null -> { if (debit == 0.0 && credit == 0.0) debit = num else credit = num }
                }
            }
        } else {
            // بدون رؤوس: افتراض RTL موثق + علامة WARNING للمراجعة (لا تخمين أعمى)
            confidence = 75; issues.add("no_header_columns")
            val byRight = texts.sortedByDescending { it.x0 }
            name = byRight.firstOrNull { !CURR.containsMatchIn(it.text) }?.text
            currency = byRight.firstOrNull { CURR.containsMatchIn(it.text) }?.text
            val ns = nums.sortedByDescending { it.first.x0 }
            when {
                ns.size >= 2 -> { credit = ns[0].second; debit = ns[1].second }
                ns.size == 1 -> { val v = ns[0].second; if (v >= 0) debit = v else credit = -v }
            }
        }

        if (name.isNullOrBlank()) { out.ignored++; return }
        val triple = ArabicNormalizer.process(name)
        out.rows.add(
            com.smartassistant.app.data.local.entity.ImportRawRow(
                sessionId = session, pageNumber = page, rowNumber = rowNum,
                nameRaw = triple.raw, nameDisplay = triple.display, nameNormalized = triple.normalized,
                credit = credit, debit = debit, currency = currency, net = debit - credit,
                status = if (confidence >= 90) "VALID" else "WARNING",
                confidenceScore = confidence,
                sourceCoordinates = "p$page:x${name.let { cells.maxByOrNull { c2 -> c2.x0 }?.x0?.toInt() ?: 0 }}",
                issues = issues.joinToString(",").ifEmpty { null },
                approved = 1
            )
        )
        out.totalCredit += credit; out.totalDebit += debit
    }
}

/** حذف بيانات جلسة استيراد سابقة (لإعادة الاستيراد بعد التصحيح) */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
