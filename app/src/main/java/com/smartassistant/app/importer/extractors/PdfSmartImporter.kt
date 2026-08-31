package com.smartassistant.app.importer.extractors

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

data class CellBox(val text: String, val x0: Float, val x1: Float, val centerY: Float) {
    val centerX: Float get() = (x0 + x1) / 2f
}

data class ColRange(val start: Float, val end: Float) {
    fun contains(x: Float) = x >= start && x <= end
}

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
 * مستورد PDF مطابق لبنية تقرير «عام#العملاء»:
 * الترتيب البصري (يسار→يمين): العملة | دائن(لك) | مدين(عليه) | الاسم
 */
object PdfSmartImporter {

    private val SKIP = Regex("#|---|===|اجمالي|المجموع|total|تاريخ الطباع|عنوان المحل|هاتف المحل|اسم الشرك", RegexOption.IGNORE_CASE)
    private val TOTAL = Regex("(ال)?اجمالي|المجموع|الإجمالي|اجمالي العمليات|الاجمالي", RegexOption.IGNORE_CASE)
    private val PAGE_FOOTER = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")
    private val CURR_TOKEN = Regex("ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd|\\$|€|£", RegexOption.IGNORE_CASE)

    fun extractWords(file: File): List<WordBox> {
        PDDocument.load(file).use { doc ->
            val c = WordCollector()
            c.setEndPage(minOf(doc.numberOfPages, 300))
            c.getText(doc)
            return c.words
        }
    }

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val words = extractWords(file)
        if (words.isEmpty()) throw IllegalStateException("لا يوجد نص قابل للاستخراج في الملف.")
        val out = ImportEngine.AnalyzeResult()
        var rowNum = 0
        var roles: Map<String, ColRange>? = null

        words.groupBy { it.page }.toSortedMap().forEach { (page, pw) ->
            for (row in groupRows(pw)) {
                val cells = splitCells(row).filter {
                    it.text.isNotEmpty() && it.text != "|" && !it.text.contains("---")
                }
                if (cells.isEmpty()) continue
                val joined = cells.joinToString(" ").trim()
                if (joined.isBlank()) continue
                if (PAGE_FOOTER.containsMatchIn(joined)) { out.ignored++; continue }
                if (SKIP.containsMatchIn(joined)) { out.ignored++; continue }
                if (TOTAL.containsMatchIn(joined)) { out.ignored++; continue }
                if (isHeader(joined)) { roles = buildRoles(cells); continue }
                if (cells.size == 1) { out.ignored++; continue }
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
        val thr = maxOf(4f, med * 0.5f)
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
        listOf("اسم", "إسم", "عميل", "رصيد", "مدين", "دائن", "عمل").count { joined.contains(it) } >= 2

    private fun roleOf(text: String): String? = when {
        text.contains("رصيد") -> "balance"
        text.contains(Regex("مدين|عليه")) -> "debit"
        text.contains("دائن") || text.trim() == "له" -> "credit"
        text.contains("عمل") -> "currency"
        text.contains("كود") -> "code"
        text.contains(Regex("اسم|إسم")) || text.contains("عميل") -> "name"
        else -> null
    }

    private fun buildRoles(cells: List<CellBox>): Map<String, ColRange> {
        val m = mutableMapOf<String, ColRange>()
        cells.forEach { c -> roleOf(c.text)?.let { r -> m[r] = ColRange(c.x0 - 12f, c.x1 + 12f) } }
        return m
    }

    private fun buildRecord(
        cells: List<CellBox>, page: Int, rowNum: Int,
        roles: Map<String, ColRange>?, kind: ImportKind,
        session: Long, out: ImportEngine.AnalyzeResult
    ) {
        if (kind == ImportKind.PRODUCT) {
            val texts = cells.filter { NumberParser.parse(it.text).value == null }
            val nums = cells.mapNotNull { c -> NumberParser.parse(c.text).value?.let { c to it } }
            val name = texts.maxByOrNull { it.x0 }?.text ?: return
            val qty = nums.maxByOrNull { it.first.x0 }?.second ?: 0.0
            out.products.add(com.smartassistant.app.data.local.entity.Product(nameRaw = ArabicNormalizer.process(name).raw) to qty)
            return
        }

        var name: String? = null; var currency: String? = null
        var credit = 0.0; var debit = 0.0
        var confidence = 95; val issues = mutableListOf<String>()

        // ===== النمط الثابت لتقرير العملاء: [عملة] [دائن] [مدين] [الاسم] =====
        val tokens = cells.flatMap { c -> c.text.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
        var ti = 0
        val currSb = StringBuilder()
        while (ti < tokens.size && CURR_TOKEN.containsMatchIn(tokens[ti])) {
            if (currSb.isNotEmpty()) currSb.append(' ')
            currSb.append(tokens[ti]); ti++
        }
        val nums2 = mutableListOf<Double>()
        while (ti < tokens.size && nums2.size < 2) {
            val v = NumberParser.parse(tokens[ti]).value ?: break
            nums2 += v; ti++
        }
        val nameCandidate = tokens.drop(ti).joinToString(" ").trim()

        when {
            nameCandidate.isNotEmpty() && nums2.size == 2 -> {
                name = nameCandidate
                credit = nums2[0]   // دائن (لك)
                debit = nums2[1]    // مدين (عليه)
                currency = currSb.toString().ifEmpty { null }
            }
            nameCandidate.isNotEmpty() && currSb.isNotEmpty() && nums2.size == 1 -> {
                name = nameCandidate
                val v = nums2[0]
                if (v >= 0) debit = v else credit = -v
                currency = currSb.toString()
                confidence = 80; issues.add("single_number")
            }
            else -> {
                // ===== احتياط: الإحداثيات =====
                confidence = 75; issues.add("no_pattern")
                val nums = cells.mapNotNull { c -> NumberParser.parse(c.text).value?.let { c to it } }
                val texts = cells.filter { NumberParser.parse(it.text).value == null }
                if (roles != null) {
                    for (c in cells) {
                        val num = NumberParser.parse(c.text).value
                        val role = roles.entries
                            .filter { it.value.contains(c.centerX) }
                            .minByOrNull { kotlin.math.abs((it.value.start + it.value.end) / 2 - c.centerX) }?.key
                        when {
                            role == "name" -> name = ((name?.plus(" ") ?: "") + c.text)
                            role == "credit" -> credit = num ?: credit
                            role == "debit" -> debit = num ?: debit
                            role == "balance" -> { val v = num ?: 0.0; if (v >= 0) debit = v else credit = -v }
                            role == "currency" -> currency = c.text
                            role == "code" -> {}
                            num == null && name == null -> name = c.text
                            num != null -> { if (debit == 0.0 && credit == 0.0) debit = num else credit = num }
                        }
                    }
                } else {
                    val byRight = texts.sortedByDescending { it.x0 }
                    name = byRight.firstOrNull { !CURR_TOKEN.containsMatchIn(it.text) }?.text
                    currency = byRight.firstOrNull { CURR_TOKEN.containsMatchIn(it.text) }?.text
                    val ns = nums.sortedByDescending { it.first.x0 }
                    when {
                        ns.size >= 2 -> { credit = ns[1].second; debit = ns[0].second }
                        ns.size == 1 -> { val v = ns[0].second; if (v >= 0) debit = v else credit = -v }
                    }
                }
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
                sourceCoordinates = "p$page",
                issues = issues.joinToString(",").ifEmpty { null },
                approved = 1
            )
        )
        out.totalCredit += credit; out.totalDebit += debit
    }
}

/** حذف بيانات جلسة سابقة + تنظيف البيانات التالفة غير المرتبطة بجلسة */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    repo.db.customerDao().deleteUnlinked()
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
