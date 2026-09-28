package com.smartassistant.app.importer.extractors

import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.text.Normalizer

/**
 * محرك PDF فقط.
 *
 * لا يفترض وجود الفاصل |؛ لأن PDFBox قد يعيد النص كمسافات أو أسطر
 * حتى لو كان الملف في الأصل جدولاً. يدعم:
 * - جداول مفصولة بـ |
 * - جداول مفصولة بمسافات/تبويبات
 * - ترتيب الأعمدة من اليمين لليسار أو العكس
 * - النص العربي المشوه من بعض ملفات PDF
 */
object PdfSmartImporter {

    private val TOTAL = Regex("إجمالي|الاجمالي|الإجمالي|المجموع|total", RegexOption.IGNORE_CASE)
    private val HEADER = Regex(
        "اسم|عميل|صنف|رصيد|دائن|مدين|له|عليه|العملة|الكمية|الوحدة|المخزن|الهاتف|جوال|phone|balance",
        RegexOption.IGNORE_CASE
    )
    private val PAGE_NOISE = Regex(
        "^(?:page|صفحة)?\\s*\\d+(?:\\s*(?:من|of|/)\\s*\\d+)?$",
        RegexOption.IGNORE_CASE
    )

    private fun isPresentationForm(c: Char): Boolean =
        c in '\uFE70'..'\uFEFF'

    private fun fixArabic(text: String): String {
        val normalized = text.split(' ').joinToString(" ") { word ->
            if (word.any(::isPresentationForm)) {
                Normalizer.normalize(word.reversed(), Normalizer.Form.NFKC)
            } else word
        }
        return normalized.replace(Regex("\\s+"), " ").trim()
    }

    private fun cells(line: String): List<String> =
        line.split(Regex("\\s*\\|\\s*|\\t+|\\s{2,}"))
            .map { fixArabic(it.trim()) }
            .filter { it.isNotEmpty() }

    private fun tokens(line: String): List<String> =
        line.split(Regex("\\s*\\|\\s*|\\t+|\\s+"))
            .map { fixArabic(it.trim()) }
            .filter { it.isNotEmpty() }

    private fun isNoise(line: String, parts: List<String>): Boolean {
        if (line.isBlank() || parts.isEmpty()) return true
        if (PAGE_NOISE.matches(line.trim())) return true
        if (TOTAL.containsMatchIn(line)) return true
        if (HEADER.containsMatchIn(line) && parts.none { com.smartassistant.app.importer.normalizers.NumberParser.parse(it).value != null }) return true
        if (parts.size == 1 && com.smartassistant.app.importer.normalizers.NumberParser.parse(parts[0]).value == null) return true
        return false
    }

    private fun numberIndexes(parts: List<String>): List<Int> =
        parts.indices.filter {
            com.smartassistant.app.importer.normalizers.NumberParser.parse(parts[it]).value != null
        }

    fun parse(file: File, shopName: String?, kind: ImportKind, session: Long): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()

        PDDocument.load(file).use { doc ->
            require(doc.numberOfPages > 0) { "ملف PDF لا يحتوي صفحات." }

            val stripper = PDFTextStripper().apply {
                setSortByPosition(true)
                setStartPage(1)
                setEndPage(minOf(doc.numberOfPages, 300))
            }

            val text = stripper.getText(doc)
                .replace('\u00A0', ' ')
                .replace("\r", "")

            if (text.isBlank()) {
                throw IllegalStateException(
                    "ملف PDF لا يحتوي نصاً قابلاً للاستخراج. إذا كان PDF عبارة عن صور ممسوحة ضوئياً، أرسل عينة منه لإضافة OCR مناسب."
                )
            }

            var rowNum = 0
            text.lines().forEach { raw ->
                val line = fixArabic(raw.trim())
                if (line.isBlank()) return@forEach
                if (shopName != null && shopName.isNotBlank() && line.contains(shopName)) return@forEach

                // نجرب شكل الجدول أولاً، ثم الشكل العام بدون الاعتماد على |.
                val parts = cells(line)
                if (isNoise(line, parts)) return@forEach

                val usable = if (parts.size >= 2) parts else tokens(line)
                if (usable.isEmpty() || isNoise(line, usable)) return@forEach

                val numeric = numberIndexes(usable)
                if (kind == ImportKind.CUSTOMER) {
                    if (numeric.size >= 2) {
                        rowNum++
                        parseCustomer(usable, numeric, session, rowNum, out)
                    }
                } else {
                    if (numeric.isNotEmpty()) {
                        rowNum++
                        parseProduct(usable, numeric, session, rowNum, out)
                    }
                }
            }
        }

        if (out.rows.isEmpty() && out.products.isEmpty()) {
            throw IllegalStateException(
                "تم فتح PDF لكن لم يتم التعرف على أي سجل. تأكد أن الملف يحتوي جدول عملاء/أرصدة أو أصناف/كميات وليس صورة فقط."
            )
        }

        return out
    }

    private fun parseCustomer(
        parts: List<String>,
        numeric: List<Int>,
        session: Long,
        rowNum: Int,
        out: ImportEngine.AnalyzeResult
    ) {
        val first = numeric.first()
        val last = numeric.last()

        val firstValue = com.smartassistant.app.importer.normalizers.NumberParser.parse(parts[first]).value ?: return
        val lastValue = com.smartassistant.app.importer.normalizers.NumberParser.parse(parts[last]).value ?: return

        // إذا كان الاسم بعد آخر رقم: [العملة, دائن, مدين, الاسم]
        // وإلا: [الاسم, دائن, مدين, ...]
        val afterLast = parts.drop(last + 1)
            .filterNot { it.equals("له", true) || it.equals("عليه", true) }
        val beforeFirst = parts.take(first)

        val name: String
        val currency: String?

        if (afterLast.isNotEmpty()) {
            name = afterLast.joinToString(" ")
            currency = parts.take(first)
                .filter { com.smartassistant.app.importer.normalizers.NumberParser.parse(it).value == null }
                .joinToString(" ").ifBlank { null }
        } else {
            name = beforeFirst.joinToString(" ")
            currency = parts.drop(last + 1)
                .filter { com.smartassistant.app.importer.normalizers.NumberParser.parse(it).value == null }
                .joinToString(" ").ifBlank { null }
        }

        if (name.isBlank() || HEADER.matches(name)) return

        val credit: Double
        val debit: Double

        // الاتجاه الشائع في تقارير الحسابات: أول رقم دائن، والثاني مدين.
        // إذا ظهرت كلمات له/عليه، نعطيها الأولوية.
        val joined = parts.joinToString(" ")
        if (Regex("له|دائن", RegexOption.IGNORE_CASE).containsMatchIn(joined) &&
            Regex("عليه|مدين", RegexOption.IGNORE_CASE).containsMatchIn(joined)
        ) {
            credit = firstValue
            debit = lastValue
        } else {
            credit = firstValue
            debit = lastValue
        }

        val t = ArabicNormalizer.process(name)
        out.rows += ImportRawRow(
            sessionId = session,
            pageNumber = 0,
            rowNumber = rowNum,
            nameRaw = t.raw,
            nameDisplay = t.display,
            nameNormalized = t.normalized,
            credit = kotlin.math.abs(credit),
            debit = kotlin.math.abs(debit),
            currency = currency,
            net = kotlin.math.abs(debit) - kotlin.math.abs(credit),
            status = "VALID",
            confidenceScore = 90,
            sourceCoordinates = "pdf:line$rowNum",
            issues = null,
            approved = 1
        )
        out.totalCredit += kotlin.math.abs(credit)
        out.totalDebit += kotlin.math.abs(debit)
    }

    private fun parseProduct(
        parts: List<String>,
        numeric: List<Int>,
        session: Long,
        rowNum: Int,
        out: ImportEngine.AnalyzeResult
    ) {
        val qtyIndex = numeric.first()
        val qty = com.smartassistant.app.importer.normalizers.NumberParser.parse(parts[qtyIndex]).value ?: return

        val afterQty = parts.drop(qtyIndex + 1)
        val beforeQty = parts.take(qtyIndex)

        // أغلب تقارير المخزون: [الكمية, الوحدة, الصنف, المخزن]
        val source = if (afterQty.isNotEmpty()) afterQty else beforeQty
        val filtered = source.filterNot {
            it.contains("المخزن") || it.equals("الرئيسي", true) || it.equals("-", true)
        }
        if (filtered.isEmpty()) return

        var unit: String? = null
        val nameParts = filtered.toMutableList()
        if (nameParts.size >= 2 &&
            com.smartassistant.app.importer.normalizers.NumberParser.parse(nameParts.first()).value == null &&
            nameParts.first().length <= 12
        ) {
            unit = nameParts.removeAt(0)
        }

        val name = nameParts.joinToString(" ").trim()
        if (name.isBlank() || HEADER.matches(name)) return

        val t = ArabicNormalizer.process(name)
        out.products += Product(nameRaw = t.raw, unit = unit) to kotlin.math.abs(qty)
        out.totalDebit += kotlin.math.abs(qty)
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
