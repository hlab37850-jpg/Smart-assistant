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
import java.text.Normalizer

/**
 * محرك نهائي مبني على البنية الحقيقية للملفين:
 * - كل سجل = سطر واحد مفصول بـ |
 * - النص العربي معكوس الأحرف -> تصحيح BiDi (عكس الكلمات + NFKD)
 */
object PdfSmartImporter {

    private val FOOTER = Regex("\\d{1,4} \\d{4}-\\d{2}-\\d{2}")
    private val TOTAL = Regex("[إا]جمالي|المجموع")

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 300))
            val lines = stripper.getText(doc).lines()
            if (kind == ImportKind.CUSTOMER) parseCustomers(lines, session, out)
            else parseProducts(lines, session, out)
        }
        return out
    }

    // ===== تصحيح العربي المعكوس =====
    private fun isArabic(c: Char): Boolean =
        (c in '؀'..'\u06FF') || (c in '\u0750'..'\u077F') ||
        (c in '\uFB50'..'\uFDFF') || (c in '\uFE70'..'\uFEFF')

    private fun fixWord(w: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            val ar = isArabic(w[i])
            var j = i
            while (j < w.length && isArabic(w[j]) == ar) j++
            val seg = w.substring(i, j)
            if (ar) {
                val rev = StringBuilder()
                for (k in seg.length - 1 downTo 0) rev.append(seg[k])
                sb.append(Normalizer.normalize(rev.toString(), Normalizer.Form.NFKD))
            } else sb.append(seg)
            i = j
        }
        return sb.toString()
    }

    private fun fixCell(cell: String): String =
        cell.split(' ').filter { it.isNotEmpty() }.map { fixWord(it) }.reversed().joinToString(" ")

    private fun cellsOf(line: String): List<String> =
        line.split('|').map { it.trim() }.filter { it.isNotEmpty() && !FOOTER.containsMatchIn(it) }

    // ===== العملاء: | العملة | دائن | مدين | الاسم | =====
    private fun parseCustomers(lines: List<String>, session: Long, out: ImportEngine.AnalyzeResult) {
        var rowNum = 0
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || !line.contains('|')) continue
            val cells = cellsOf(line)
            if (cells.size < 2) continue
            if (cells.distinct().size == 1) continue
            val fixed = cells.map { fixCell(it) }
            val joined = fixed.joinToString(" ")
            if (TOTAL.containsMatchIn(joined)) continue
            if (joined.contains("العملة") && joined.contains("مدين")) continue
            if (joined.contains("#")) continue
            if (fixed.size < 4) continue
            if (NumberParser.parse(fixed[0]).value != null) continue
            val credit = NumberParser.parse(fixed[1]).value
            val debit = NumberParser.parse(fixed[2]).value
            val name = fixed.drop(3).joinToString(" ").trim()
            if (credit == null || debit == null || name.isEmpty()) { out.ignored++; continue }
            rowNum++
            val t = ArabicNormalizer.process(name)
            out.rows.add(ImportRawRow(
                sessionId = session, pageNumber = 0, rowNumber = rowNum,
                nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                credit = credit, debit = debit, currency = fixed[0],
                net = debit - credit, status = "VALID", confidenceScore = 95,
                sourceCoordinates = "line$rowNum", issues = null, approved = 1))
            out.totalCredit += credit
            out.totalDebit += debit
        }
    }

    // ===== الأصناف: | الكمية | الوحدة | الصنف | المخزن الرئيسي | =====
    private class PRec(var name: String, val unit: String?, val qty: Double)

    private fun parseProducts(lines: List<String>, session: Long, out: ImportEngine.AnalyzeResult) {
        val list = mutableListOf<PRec>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || !line.contains('|')) continue
            val cells = cellsOf(line)
            if (cells.size < 2) continue
            if (cells.distinct().size == 1) continue
            val fixed = cells.map { fixCell(it) }
            val joined = fixed.joinToString(" ")
            if (TOTAL.containsMatchIn(joined)) continue
            if (joined.contains("الكمية") && joined.contains("الصنف")) continue
            if (joined.contains("#")) continue
            val qty = NumberParser.parse(fixed[0]).value
            if (qty != null) {
                val unit = if (fixed.size > 1 && NumberParser.parse(fixed[1]).value == null && fixed[1] != "-") fixed[1] else null
                val start = if (unit != null) 2 else 1
                val name = if (fixed.size > start)
                    fixed.subList(start, fixed.size).filterNot { it.contains("المخزن") || it == "الرئيسي" }.joinToString(" ").trim()
                else ""
                if (name.isNotEmpty()) list.add(PRec(name, unit, qty))
            } else if (list.isNotEmpty()) {
                val extra = fixed.filterNot { it.contains("المخزن") || it == "الرئيسي" }.joinToString(" ").trim()
                if (extra.isNotEmpty()) {
                    val last = list[list.size - 1]
                    last.name = (last.name + " " + extra).trim()
                }
            } else out.ignored++
        }
        list.forEachIndexed { idx, p ->
            val t = ArabicNormalizer.process(p.name)
            out.rows.add(ImportRawRow(
                sessionId = session, pageNumber = 0, rowNumber = idx + 1,
                nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                credit = 0.0, debit = p.qty, currency = p.unit,
                net = p.qty, status = "VALID", confidenceScore = 95,
                sourceCoordinates = "prod$idx", issues = null, approved = 1))
            out.products.add(Product(nameRaw = t.raw, unit = p.unit) to p.qty)
        }
    }
}

suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
