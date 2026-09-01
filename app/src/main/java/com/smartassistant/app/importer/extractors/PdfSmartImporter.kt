package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.ImportRawRow
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

data class CellBox(val text: String, val x0: Float, val x1: Float, val centerY: Float) {
    val centerX: Float get() = (x0 + x1) / 2f
}

data class ColRange(val start: Float, val end: Float) {
    fun contains(x: Float): Boolean = x >= start && x <= end
}

private class WordCollector : PDFTextStripper() {
    val words = mutableListOf<WordBox>()
    init { setSortByPosition(true) }
    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (textPositions.isNotEmpty()) {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (tp in textPositions) {
                if (tp.x < x0) x0 = tp.x
                if (tp.y - tp.height < y0) y0 = tp.y - tp.height
                if (tp.x + tp.width > x1) x1 = tp.x + tp.width
                if (tp.y > y1) y1 = tp.y
            }
            val t = text.trim()
            if (t.isNotEmpty()) {
                words.add(WordBox(t, getCurrentPageNo(), x0, y0, x1, y1))
            }
        }
        super.writeString(text, textPositions)
    }
}

object PdfSmartImporter {

    private val CURR_TOKEN = Regex("""ريال|يمني|دولار|دينار|درهم|جنيه|سعودي|usd""", RegexOption.IGNORE_CASE)
    private val HDR_KEYS = listOf("اسم", "إسم", "عميل", "رصيد", "مدين", "دائن", "عمل", "كمية", "كميه", "وحدة", "وحده", "صنف", "مخزن", "المتبقي")
    private val TOTAL_KEYWORDS = listOf("اجمالي", "المجموع", "الإجمالي", "الاجمالي")
    private val PIPE = "|"
    private val HASH = "#"
    private val DASH = "---"

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val collector = WordCollector()
            collector.setEndPage(minOf(doc.numberOfPages, 300))
            collector.getText(doc)

            val logicalLines = buildLogicalLines(collector.words)
            var rowNum = 0
            var roles: Map<String, ColRange>? = null

            for (cells in logicalLines) {
                val joined = cells.joinToString(" ") { it.text }.trim()
                if (joined.isEmpty()) continue
                if (joined.contains(HASH) || joined.contains(DASH) || joined.contains("===")) continue
                if (isFooter(joined)) continue
                if (isTotal(joined)) continue
                if (shopName != null && joined.contains(shopName)) continue
                if (joined.contains("المتبقي") && cells.distinctBy { it.text }.size <= 2) continue

                if (isHeader(joined)) {
                    roles = buildRoles(cells)
                    continue
                }
                if (cells.size < 2) continue

                rowNum++
                if (kind == ImportKind.CUSTOMER) {
                    parseCustomer(cells, session, rowNum, roles, out)
                } else {
                    parseProduct(cells, session, rowNum, roles, out)
                }
            }
        }
        return out
    }

    private fun isFooter(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size != 2) return false
        val first = parts[0]
        val second = parts[1]
        val numOk = first.all { it.isDigit() } && first.length in 1..4
        val dateOk = second.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
        return numOk && dateOk
    }

    private fun isTotal(line: String): Boolean {
        val lower = line.lowercase()
        return TOTAL_KEYWORDS.any { lower.contains(it) }
    }

    private fun isHeader(line: String): Boolean {
        return HDR_KEYS.count { line.contains(it) } >= 2
    }

    private fun buildLogicalLines(words: List<WordBox>): List<List<CellBox>> {
        val out = mutableListOf<List<CellBox>>()
        words.groupBy { it.page }.toSortedMap().forEach { (_, pageWords) ->
            val sorted = pageWords.sortedWith(compareBy({ it.centerY }, { it.x0 }))
            val current = mutableListOf<WordBox>()
            var lastY: Float? = null
            fun flush() {
                if (current.isNotEmpty()) {
                    val row = current.sortedBy { it.x0 }
                    out.add(splitIntoCells(row))
                    current.clear()
                }
            }
            for (w in sorted) {
                val ly = lastY
                val threshold = maxOf(6f, w.height * 0.8f)
                if (ly != null && kotlin.math.abs(w.centerY - ly) > threshold) {
                    flush()
                }
                current.add(w)
                lastY = w.centerY
            }
            flush()
        }
        return out
    }

    private fun splitIntoCells(row: List<WordBox>): List<CellBox> {
        val sorted = row.sortedBy { it.x0 }
        if (sorted.isEmpty()) return emptyList()
        if (sorted.size == 1) {
            return listOf(CellBox(sorted[0].text, sorted[0].x0, sorted[0].x1, sorted[0].centerY))
        }
        val gaps = mutableListOf<Float>()
        for (i in 1 until sorted.size) {
            gaps.add(sorted[i].x0 - sorted[i - 1].x1)
        }
        val sortedGaps = gaps.sorted()
        val median = if (sortedGaps.isEmpty()) 0f else sortedGaps[sortedGaps.size / 2]
        val threshold = maxOf(8f, median * 2.5f)

        val cells = mutableListOf<CellBox>()
        var text = StringBuilder(sorted[0].text)
        var x0 = sorted[0].x0
        var x1 = sorted[0].x1
        val cy = sorted[0].centerY

        for (i in 1 until sorted.size) {
            val gap = sorted[i].x0 - sorted[i - 1].x1
            if (gap > threshold) {
                cells.add(CellBox(text.toString().trim(), x0, x1, cy))
                text = StringBuilder(sorted[i].text)
                x0 = sorted[i].x0
                x1 = sorted[i].x1
            } else {
                text.append(" ").append(sorted[i].text)
                x1 = sorted[i].x1
            }
        }
        cells.add(CellBox(text.toString().trim(), x0, x1, cy))
        return cells.filter { it.text.isNotEmpty() && it.text != PIPE }
    }

    private fun roleOf(text: String): String? = when {
        text.contains(Regex("كميه|كمية")) -> "qty"
        text.contains(Regex("وحده|وحدة")) -> "unit"
        text.contains("مخزن") -> "skip"
        text.contains("رصيد") -> "balance"
        text.contains(Regex("مدين|عليه")) -> "debit"
        text.contains("دائن") || text.trim() == "له" -> "credit"
        text.contains("عمل") -> "currency"
        text.contains("كود") -> "code"
        text.contains(Regex("اسم|إسم")) || text.contains("عميل") || text.contains("صنف") -> "name"
        else -> null
    }

    private fun buildRoles(cells: List<CellBox>): Map<String, ColRange> {
        val m = mutableMapOf<String, ColRange>()
        for (c in cells) {
            val r = roleOf(c.text)
            if (r != null) {
                m[r] = ColRange(c.x0 - 12f, c.x1 + 12f)
            }
        }
        return m
    }

    private fun roleAt(roles: Map<String, ColRange>, c: CellBox): String? {
        return roles.entries
            .filter { it.value.contains(c.centerX) }
            .minByOrNull { kotlin.math.abs((it.value.start + it.value.end) / 2 - c.centerX) }
            ?.key
    }

    private fun parseCustomer(
        cells: List<CellBox>, session: Long, rowNum: Int,
        roles: Map<String, ColRange>?, out: ImportEngine.AnalyzeResult
    ) {
        var name: String? = null
        var currency: String? = null
        var credit = 0.0
        var debit = 0.0
        var confidence = 95
        val issues = mutableListOf<String>()

        if (roles != null && roles.containsKey("name")) {
            for (c in cells) {
                val num = NumberParser.parse(c.text).value
                when (roleAt(roles, c)) {
                    "name" -> name = (name?.plus(" ") ?: "") + c.text
                    "credit" -> if (num != null) credit = num
                    "debit" -> if (num != null) debit = num
                    "balance" -> {
                        val v = num ?: 0.0
                        if (v >= 0) debit = v else credit = -v
                    }
                    "currency" -> currency = c.text
                    "code", "skip" -> {}
                    else -> {
                        if (num == null && CURR_TOKEN.containsMatchIn(c.text)) currency = c.text
                        else if (num == null && name == null) name = c.text
                        else if (num != null) {
                            if (debit == 0.0 && credit == 0.0) debit = num else credit = num
                        }
                    }
                }
            }
        } else {
            confidence = 75
            issues.add("no_header_detected")
            val tokens = cells.flatMap { it.text.split(Regex("\\s+")) }.filter { it.isNotEmpty() }
            var ti = 0
            val currSb = StringBuilder()
            while (ti < tokens.size && CURR_TOKEN.containsMatchIn(tokens[ti])) {
                if (currSb.isNotEmpty()) currSb.append(" ")
                currSb.append(tokens[ti])
                ti++
            }
            val nums = mutableListOf<Double>()
            while (ti < tokens.size && nums.size < 2) {
                val v = NumberParser.parse(tokens[ti]).value ?: break
                nums.add(v)
                ti++
            }
            val nameCandidate = tokens.drop(ti).joinToString(" ").trim()
            if (nameCandidate.isNotEmpty() && nums.size == 2) {
                name = nameCandidate
                credit = nums[0]
                debit = nums[1]
                currency = currSb.toString().ifEmpty { null }
            } else if (nameCandidate.isNotEmpty() && currSb.isNotEmpty() && nums.size == 1) {
                name = nameCandidate
                val v = nums[0]
                if (v >= 0) debit = v else credit = -v
                currency = currSb.toString()
                confidence = 80
            } else {
                val byRight = cells.filter { NumberParser.parse(it.text).value == null }
                    .sortedByDescending { it.x0 }
                name = byRight.firstOrNull { !CURR_TOKEN.containsMatchIn(it.text) }?.text
                currency = byRight.firstOrNull { CURR_TOKEN.containsMatchIn(it.text) }?.text
                val ns = cells.mapNotNull { c -> NumberParser.parse(c.text).value?.let { c to it } }
                    .sortedByDescending { it.first.x0 }
                when {
                    ns.size >= 2 -> { credit = ns[1].second; debit = ns[0].second }
                    ns.size == 1 -> {
                        val v = ns[0].second
                        if (v >= 0) debit = v else credit = -v
                    }
                }
            }
        }

        if (name.isNullOrBlank()) {
            out.ignored++
            return
        }
        val triple = ArabicNormalizer.process(name)
        out.rows.add(ImportRawRow(
            sessionId = session, pageNumber = 0, rowNumber = rowNum,
            nameRaw = triple.raw, nameDisplay = triple.display, nameNormalized = triple.normalized,
            credit = credit, debit = debit,
            currency = currency, net = debit - credit,
            status = if (confidence >= 90) "VALID" else "WARNING",
            confidenceScore = confidence,
            sourceCoordinates = "line$rowNum",
            issues = issues.joinToString(",").ifEmpty { null },
            approved = 1
        ))
        out.totalCredit += credit
        out.totalDebit += debit
    }

    private fun parseProduct(
        cells: List<CellBox>, session: Long, rowNum: Int,
        roles: Map<String, ColRange>?, out: ImportEngine.AnalyzeResult
    ) {
        var name: String? = null
        var unit: String? = null
        var qty = 0.0

        if (roles != null && roles.containsKey("name")) {
            for (c in cells) {
                when (roleAt(roles, c)) {
                    "name" -> name = c.text
                    "unit" -> unit = c.text
                    "qty" -> qty = NumberParser.parse(c.text).value ?: 0.0
                    "skip" -> {}
                    else -> {
                        if (name == null && NumberParser.parse(c.text).value == null) name = c.text
                    }
                }
            }
        } else {
            val texts = cells.filter { NumberParser.parse(it.text).value == null }
            val nums = cells.mapNotNull { c -> NumberParser.parse(c.text).value?.let { c to it } }
            name = texts.maxByOrNull { it.x0 }?.text
            qty = nums.minByOrNull { it.first.x0 }?.second ?: 0.0
        }

        if (name.isNullOrBlank()) {
            out.ignored++
            return
        }
        val t = ArabicNormalizer.process(name)
        out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
    }
}

suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
