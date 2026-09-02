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
 * محرك PDF النهائي - يعالج:
 * 1. النص العربي المعكوس (Arabic Presentation Forms)
 * 2. السطور المفصولة بـ |
 * 3. استخراج العملاء والأصناف
 */
object PdfSmartImporter {

    private val FOOTER = Regex("\\d{1,4} \\d{4}-\\d{2}-\\d{2}")
    private val TOTAL = Regex("[إا]جمالي|المجموع|الإجمالي|الاجمالي")
    
    // فحص إذا كان النص يحتوي على حروف عربية معكوسة (Presentation Forms)
    private fun isArabicPresentationForm(c: Char): Boolean =
        c in '\uFE70'..'\uFEFF'
    
    // عكس الحروف العربية المعكوسة وتطبيعها
    private fun reverseArabicWord(word: String): String {
        if (word.isEmpty()) return word
        val hasArabic = word.any { isArabicPresentationForm(it) }
        if (!hasArabic) return word
        
        // عكس الكلمة
        val reversed = word.reversed()
        // تطبيع NFKD يحول Presentation Forms إلى حروف أساسية
        return Normalizer.normalize(reversed, Normalizer.Form.NFKD)
    }
    
    // معالجة سطر كامل: عكس الكلمات العربية المعكوسة
    private fun fixArabicText(text: String): String {
        return text.split(' ').map { reverseArabicWord(it) }.joinToString(" ")
    }
    
    // استخراج الخلايا من سطر مفصول بـ |
    private fun extractCells(line: String): List<String> {
        return line.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { fixArabicText(it) }
    }
    
    // فحص إذا كان السطر ضوضاء (عنوان، رأس، فاصل، تذييل، إجمالي)
    private fun isNoise(line: String, cells: List<String>): Boolean {
        if (cells.isEmpty()) return true
        if (cells.size < 2) return true
        
        val joined = cells.joinToString(" ")
        
        // الفواصل
        if (line.contains("---") || line.contains("===")) return true
        
        // التذييل (رقم صفحة + تاريخ)
        if (FOOTER.containsMatchIn(line)) return true
        
        // الإجماليات
        if (TOTAL.containsMatchIn(joined)) return true
        
        // العناوين المتكررة
        if (cells.distinct().size == 1) return true
        
        // رؤوس الأعمدة
        if (joined.contains("العملة") && joined.contains("مدين")) return true
        if (joined.contains("الكمية") && joined.contains("الصنف")) return true
        if (joined.contains("عام#العملاء") || joined.contains("المخزون المتبقي")) return true
        
        return false
    }

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
                if (line.isEmpty() || !line.contains("|")) continue
                
                val cells = extractCells(line)
                if (isNoise(line, cells)) continue
                
                rowNum++
                if (kind == ImportKind.CUSTOMER) {
                    parseCustomer(cells, session, rowNum, out)
                } else {
                    parseProduct(cells, session, rowNum, out)
                }
            }
        }
        return out
    }

    /**
     * عملاء: [العملة] [دائن] [مدين] [الاسم]
     * مثال: | ريال يمني | 0 | 38,360 | حمزة صاحب الثلج |
     */
    private fun parseCustomer(cells: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        if (cells.size < 3) { out.ignored++; return }
        
        var currency: String? = null
        var credit = 0.0
        var debit = 0.0
        var name: String? = null
        
        var i = 0
        
        // الخلية الأولى قد تكون العملة
        if (i < cells.size && NumberParser.parse(cells[i]).value == null) {
            currency = cells[i]
            i++
        }
        
        // دائن (له)
        if (i < cells.size) {
            credit = NumberParser.parse(cells[i]).value ?: 0.0
            i++
        }
        
        // مدين (عليه)
        if (i < cells.size) {
            debit = NumberParser.parse(cells[i]).value ?: 0.0
            i++
        }
        
        // الاسم (باقي الخلايا)
        name = cells.drop(i).joinToString(" ").trim()
        
        if (name.isNullOrEmpty()) { out.ignored++; return }
        
        val t = ArabicNormalizer.process(name)
        out.rows.add(ImportRawRow(
            sessionId = session, pageNumber = 0, rowNumber = rowNum,
            nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
            credit = credit, debit = debit,
            currency = currency, net = debit - credit,
            status = "VALID", confidenceScore = 95,
            sourceCoordinates = "line$rowNum", issues = null, approved = 1))
        out.totalCredit += credit
        out.totalDebit += debit
    }

    /**
     * أصناف: [الكمية] [الوحدة] [الصنف] [المخزن]
     * مثال: | 112 | حبة | ركب أمريكي سن نحاس 1/2 هـ | المخزن الرئيسي |
     */
    private fun parseProduct(cells: List<String>, session: Long, rowNum: Int, out: ImportEngine.AnalyzeResult) {
        if (cells.size < 2) { out.ignored++; return }
        
        val qty = NumberParser.parse(cells[0]).value
        if (qty == null) { out.ignored++; return }
        
        var i = 1
        var unit: String? = null
        
        // الوحدة (إذا كانت خلية بدون رقم)
        if (i < cells.size && NumberParser.parse(cells[i]).value == null) {
            val candidate = cells[i]
            if (candidate != "-" && !candidate.contains("المخزن") && !candidate.contains("الرئيسي")) {
                unit = candidate
                i++
            }
        }
        
        // الصنف (باقي الخلايا حتى "المخزن الرئيسي")
        val nameParts = mutableListOf<String>()
        while (i < cells.size) {
            val cell = cells[i]
            if (cell.contains("المخزن") || cell.contains("الرئيسي")) {
                i++
                continue
            }
            nameParts.add(cell)
            i++
        }
        val name = nameParts.joinToString(" ").trim()
        
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
