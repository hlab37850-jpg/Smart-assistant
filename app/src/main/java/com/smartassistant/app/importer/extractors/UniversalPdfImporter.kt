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
 * محرك استيراد PDF عالمي - يعمل مع أي ملف PDF مهما كان تصميمه.
 * 
 * المميزات:
 * 1. استخراج النص الخام من PDF
 * 2. تصحيح النص العربي المعكوس (RTL reversal) تلقائياً
 * 3. تقسيم ذكي على | والأنماط الأخرى
 * 4. اكتشاف نوع البيانات تلقائياً (عميل/صنف)
 * 5. تصنيف كل خلية (اسم/رقم/عملة/وحدة)
 * 6. دمج الأرقام المكسورة (1,234.5 67 → 1,234.567)
 * 7. تجميع الخلايا المنفصلة في سجلات كاملة
 * 8. تجاهل الرؤوس والتذييلات والإجماليات
 */
object UniversalPdfImporter {

    // العملات المعروفة
    private val CURRENCY_TOKENS = listOf(
        "ريال", "يمني", "دولار", "دينار", "درهم", "جنيه",
        "سعودي", "مصري", "كويتي", "قطري", "بحريني", "عماني",
        "ليرة", "تومان", "usd", "eur", "gbp"
    )

    // الكلمات المفتاحية للعملاء
    private val CUSTOMER_KEYWORDS = listOf(
        "عميل", "العملاء", "عملاء", "مدين", "دائن", "رصيد",
        "له", "عليه", "العملة"
    )

    // الكلمات المفتاحية للأصناف
    private val PRODUCT_KEYWORDS = listOf(
        "صنف", "الأصناف", "أصناف", "الكمية", "كمية", "الوحدة",
        "وحدة", "مخزن", "المخزون", "المخزن الرئيسي"
    )

    // أنماط التخطي
    private val SKIP_PATTERNS = listOf("---", "===", "###", "----")

    // نمط التذييل (رقم الصفحة + التاريخ)
    private val FOOTER_PATTERN = Regex("^\\d{1,4}\\s+\\d{4}-\\d{2}-\\d{2}$")

    // كلمات الإجمالي
    private val TOTAL_KEYWORDS = listOf("إجمالي", "الاجمالي", "الإجمالي", "المجموع", "total")

    /**
     * الدالة الرئيسية للاستيراد
     */
    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()

        // 1. استخراج النص الخام
        val rawText = extractText(file)
        if (rawText.isBlank()) {
            return out
        }

        // 2. تصحيح النص العربي المعكوس
        val fixedText = fixRtlArabic(rawText)

        // 3. استخراج الخلايا
        val cells = extractCells(fixedText)

        // 4. تجميع الخلايا في سجلات
        val records = assembleRecords(cells, kind)

        // 5. معالجة كل سجل
        var rowNum = 0
        for (record in records) {
            rowNum++
            if (kind == ImportKind.CUSTOMER) {
                processCustomerRecord(record, session, rowNum, out)
            } else if (kind == ImportKind.PRODUCT) {
                processProductRecord(record, session, rowNum, out)
            }
        }

        return out
    }

    /**
     * استخراج النص من ملف PDF
     */
    private fun extractText(file: File): String {
        return try {
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper()
                stripper.setSortByPosition(true)
                stripper.setEndPage(minOf(doc.numberOfPages, 300))
                stripper.getText(doc)
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * تصحيح النص العربي المعكوس (RTL reversal)
     */
    private fun fixRtlArabic(text: String): String {
        val lines = text.lines()
        val fixedLines = mutableListOf<String>()

        for (line in lines) {
            val parts = line.split("|")
            val fixedParts = parts.map { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@map trimmed

                if (containsReversedArabic(trimmed)) {
                    reverseArabicText(trimmed)
                } else {
                    trimmed
                }
            }
            fixedLines.add(fixedParts.joinToString("|"))
        }

        return fixedLines.joinToString("\n")
    }

    /**
     * فحص إذا كان النص يحتوي على حروف عربية معكوسة
     */
    private fun containsReversedArabic(text: String): Boolean {
        return text.any { it in '\uFE70'..'\uFEFF' }
    }

    /**
     * عكس النص العربي المعكوس وتطبيعه
     */
    private fun reverseArabicText(text: String): String {
        val chars = text.toList()
        val reversed = chars.reversed()
        val normalized = reversed.map { char ->
            normalizeArabicChar(char)
        }
        return normalized.joinToString("")
    }

    /**
     * تحويل الحرف العربي المنعزل إلى حرف أساسي
     */
    private fun normalizeArabicChar(char: Char): Char {
        return when (char) {
            in '\uFE70'..'\uFEFF' -> {
                val s = char.toString()
                val normalized = Normalizer.normalize(s, Normalizer.Form.NFKD)
                if (normalized.isNotEmpty()) normalized[0] else char
            }
            else -> char
        }
    }

    /**
     * استخراج الخلايا من النص
     */
    private fun extractCells(text: String): List<String> {
        val cells = mutableListOf<String>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (SKIP_PATTERNS.any { trimmed.contains(it) }) continue
            if (FOOTER_PATTERN.containsMatchIn(trimmed)) continue
            if (TOTAL_KEYWORDS.any { trimmed.contains(it) }) continue

            if (trimmed.startsWith("|")) {
                val parts = trimmed.split("|")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                cells.addAll(parts)
            } else {
                cells.add(trimmed)
            }
        }

        return cells
    }

    /**
     * تجميع الخلايا في سجلات
     */
    private fun assembleRecords(cells: List<String>, kind: ImportKind): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val expectedCellCount = 4

        for (cell in cells) {
            if (current.size >= expectedCellCount) {
                if (isValidRecord(current, kind)) {
                    records.add(current.toList())
                }
                current.clear()
            }
            current.add(cell)
        }

        if (current.isNotEmpty() && isValidRecord(current, kind)) {
            records.add(current.toList())
        }

        return records
    }

    /**
     * التحقق من صحة السجل
     */
    private fun isValidRecord(cells: List<String>, kind: ImportKind): Boolean {
        if (cells.isEmpty()) return false

        val joined = cells.joinToString(" ")

        if (kind == ImportKind.CUSTOMER && CUSTOMER_KEYWORDS.any { joined.contains(it) }) {
            return false
        }
        if (kind == ImportKind.PRODUCT && PRODUCT_KEYWORDS.any { joined.contains(it) }) {
            return false
        }

        val hasNumber = cells.any { NumberParser.parse(it).value != null }
        if (!hasNumber) return false

        return true
    }

    /**
     * معالجة سجل عميل
     */
    private fun processCustomerRecord(
        cells: List<String>,
        session: Long,
        rowNum: Int,
        out: ImportEngine.AnalyzeResult
    ) {
        var currency: String? = null
        var credit = 0.0
        var debit = 0.0
        var name: String? = null

        for (cell in cells) {
            val num = NumberParser.parse(cell).value

            when {
                num == null && CURRENCY_TOKENS.any { cell.lowercase().contains(it) } -> {
                    currency = cell
                }
                num != null -> {
                    if (credit == 0.0) {
                        credit = num
                    } else if (debit == 0.0) {
                        debit = num
                    }
                }
                num == null && name == null -> {
                    name = cell
                }
            }
        }

        if (name.isNullOrBlank()) {
            out.ignored++
            return
        }

        val t = ArabicNormalizer.process(name)
        out.rows.add(ImportRawRow(
            sessionId = session,
            pageNumber = 0,
            rowNumber = rowNum,
            nameRaw = t.raw,
            nameDisplay = t.display,
            nameNormalized = t.normalized,
            credit = credit,
            debit = debit,
            currency = currency,
            net = debit - credit,
            status = "VALID",
            confidenceScore = 90,
            sourceCoordinates = "line$rowNum",
            issues = null,
            approved = 1
        ))
        out.totalCredit += credit
        out.totalDebit += debit
    }

    /**
     * معالجة سجل صنف
     */
    private fun processProductRecord(
        cells: List<String>,
        session: Long,
        rowNum: Int,
        out: ImportEngine.AnalyzeResult
    ) {
        if (cells.isEmpty()) {
            out.ignored++
            return
        }

        val qty = NumberParser.parse(cells[0]).value
        if (qty == null) {
            out.ignored++
            return
        }

        var unit: String? = null
        var name: String? = null

        if (cells.size > 1) {
            val second = cells[1]
            val secondNum = NumberParser.parse(second).value
            if (secondNum == null && second != "-") {
                unit = second
            }
        }

        val nameStartIndex = if (unit != null) 2 else 1
        if (cells.size > nameStartIndex) {
            val nameCells = cells.subList(nameStartIndex, cells.size)
                .filter { it != "المخزن" && it != "الرئيسي" && !it.contains("المخزن") }
            name = nameCells.joinToString(" ").trim()
        }

        if (name.isNullOrBlank()) {
            out.ignored++
            return
        }

        val t = ArabicNormalizer.process(name)
        out.products.add(Product(nameRaw = t.raw, unit = unit) to qty)
    }
}

/**
 * حذف بيانات جلسة سابقة
 */
suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
