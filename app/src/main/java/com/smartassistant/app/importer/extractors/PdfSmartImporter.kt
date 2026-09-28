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
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.text.Normalizer
import kotlin.math.abs

/**
 * محرك استيراد PDF متخصص في التقارير الجدولية العربية.
 *
 * الملفات المستهدفة حالياً:
 * 1) تقرير العملاء: الاسم | مدين | دائن | العملة
 * 2) تقرير المخزون: اسم المخزن | الصنف | الوحدة | الكمية
 *
 * لا نعتمد على أسطر النص التي ينتجها PDFBox مباشرة.
 * نجمع الكلمات حسب إحداثي Y أولاً حتى لا تتحول خلية في نفس الصف
 * إلى سطر مستقل.
 */
object PdfSmartImporter {

    private data class PdfLine(
        val page: Int,
        val y: Float,
        val text: String
    )

    private class TableStripper : PDFTextStripper() {
        val lines = mutableListOf<PdfLine>()

        init {
            sortByPosition = true
            wordSeparator = " "
            lineSeparator = "\n"
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return

            val y = textPositions.map { it.yDirAdj }.average().toFloat()
            lines += PdfLine(
                page = currentPageNo,
                y = y,
                text = text
            )
        }
    }

    private val CUSTOMER_HEADER = Regex(
        "الإسم|الاسم|مدين|دائن|العملة|عام#العملاء|العملاء",
        RegexOption.IGNORE_CASE
    )

    private val STOCK_HEADER = Regex(
        "المخزون المتبقي|اسم المخزن|الصنف|الوحدة|الكمية",
        RegexOption.IGNORE_CASE
    )

    private val TOTAL_HEADER = Regex(
        "إجمالي|الاجمالي|الإجمالي|المجموع|total",
        RegexOption.IGNORE_CASE
    )

    private val DATE = Regex("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}$")

    private val UNITS = setOf(
        "حبة", "حبه", "باكت", "كيس", "علبة", "علب",
        "لفة", "لفه", "ل", "ك", "كيلو", "كجم", "جرام",
        "متر", "م", "قطعة", "قطعه", "صندوق", "كرتون"
    )

    private fun clean(text: String): String =
        Normalizer.normalize(
            text.replace('\u00A0', ' ').replace('\r', ' '),
            Normalizer.Form.NFKC
        ).replace(Regex("\\s+"), " ").trim()

    private fun tokenize(line: String): List<String> =
        clean(line)
            .split(Regex("\\s+|\\|"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * تقرير المخزون في العينة يستخدم الفاصلة كفاصل عشري:
     * 22,275 = 22.275
     * 0,802 = 0.802
     * لذلك التحويل خاص بالكميات فقط، بينما أرقام العملاء
     * مثل 46,375 تبقى 46,375.
     */
    private fun parseQuantity(raw: String): Double? {
        val s = NumberParser.normalize(raw)
            .replace(" ", "")
            .trim()

        if (s.matches(Regex("\\d+,\\d{1,3}"))) {
            return s.replace(',', '.').toDoubleOrNull()
        }

        return NumberParser.parse(s).value
    }

    private fun isDateOrFooterToken(token: String): Boolean =
        DATE.matches(token) || token == "2026-08-31"

    private fun groupLines(lines: List<PdfLine>): List<PdfLine> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedWith(
            compareBy<PdfLine> { it.page }.thenBy { it.y }
        )

        val groups = mutableListOf<MutableList<PdfLine>>()

        for (line in sorted) {
            val last = groups.lastOrNull()
            if (
                last != null &&
                last.last().page == line.page &&
                abs(last.map { it.y }.average().toFloat() - line.y) <= 4.0f
            ) {
                last += line
            } else {
                groups += mutableListOf(line)
            }
        }

        return groups.map { group ->
            PdfLine(
                page = group.first().page,
                y = group.map { it.y }.average().toFloat(),
                text = group.joinToString(" ") { it.text }.let(::clean)
            )
        }
    }

    fun parse(
        file: File,
        shopName: String?,
        kind: ImportKind,
        session: Long
    ): ImportEngine.AnalyzeResult {
        val out = ImportEngine.AnalyzeResult()

        PDDocument.load(file).use { document ->
            require(document.numberOfPages > 0) {
                "ملف PDF لا يحتوي صفحات."
            }

            val stripper = TableStripper().apply {
                startPage = 1
                endPage = minOf(document.numberOfPages, 300)
            }

            stripper.getText(document)

            val rows = groupLines(stripper.lines)
            if (rows.isEmpty()) {
                throw IllegalStateException("لم يتم استخراج أي صف من PDF.")
            }

            var rowNumber = 0

            for (row in rows) {
                val line = clean(row.text)
                if (line.isBlank()) continue
                if (shopName != null && shopName.isNotBlank() && line.contains(shopName)) continue
                if (TOTAL_HEADER.containsMatchIn(line)) continue

                when (kind) {
                    ImportKind.CUSTOMER -> {
                        if (CUSTOMER_HEADER.containsMatchIn(line)) continue
                        val parsed = parseCustomerRow(line) ?: continue

                        rowNumber++
                        addCustomer(
                            parsed, session, rowNumber, row.page, row.y, out
                        )
                    }

                    ImportKind.PRODUCT -> {
                        if (STOCK_HEADER.containsMatchIn(line)) continue
                        val parsed = parseStockRow(line) ?: continue

                        rowNumber++
                        addProduct(
                            parsed, session, rowNumber, row.page, row.y, out
                        )
                    }
                }
            }
        }

        if (out.rows.isEmpty() && out.products.isEmpty()) {
            throw IllegalStateException(
                when (kind) {
                    ImportKind.CUSTOMER ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف عملاء بالشكل: الاسم + مدين + دائن + العملة."

                    ImportKind.PRODUCT ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف مخزون بالشكل: المخزن + الصنف + الوحدة + الكمية."
                }
            )
        }

        return out
    }

    private data class CustomerParsed(
        val name: String,
        val debit: Double,
        val credit: Double,
        val currency: String
    )

    /**
     * الشكل الحقيقي:
     * عدنان ابراهيم درويش(البنشر) 46,375 0 ريال يمني
     */
    private fun parseCustomerRow(line: String): CustomerParsed? {
        val tokens = tokenize(line)
        if (tokens.size < 4) return null

        val numericIndexes = tokens.indices.filter {
            !isDateOrFooterToken(tokens[it]) &&
                NumberParser.parse(tokens[it]).value != null
        }

        if (numericIndexes.size < 2) return null

        val first = numericIndexes[0]
        val second = numericIndexes[1]

        if (first <= 0) return null

        val name = tokens.subList(0, first).joinToString(" ").trim()
        if (name.isBlank()) return null

        val currency = tokens.drop(second + 1).joinToString(" ").trim()
        if (currency.isBlank() || !currency.contains("ريال", ignoreCase = true)) {
            return null
        }

        val debit = NumberParser.parse(tokens[first]).value ?: return null
        val credit = NumberParser.parse(tokens[second]).value ?: return null

        return CustomerParsed(
            name = name,
            debit = abs(debit),
            credit = abs(credit),
            currency = currency
        )
    }

    private fun addCustomer(
        parsed: CustomerParsed,
        session: Long,
        rowNumber: Int,
        page: Int,
        y: Float,
        out: ImportEngine.AnalyzeResult
    ) {
        val t = ArabicNormalizer.process(parsed.name)

        out.rows += ImportRawRow(
            sessionId = session,
            pageNumber = page,
            rowNumber = rowNumber,
            nameRaw = t.raw,
            nameDisplay = t.display,
            nameNormalized = t.normalized,
            credit = parsed.credit,
            debit = parsed.debit,
            currency = parsed.currency,
            net = parsed.debit - parsed.credit,
            status = "VALID",
            confidenceScore = 98,
            sourceCoordinates = "pdf:p" + page + ":y" + y,
            issues = null,
            approved = 1
        )

        out.totalCredit += parsed.credit
        out.totalDebit += parsed.debit
    }

    private data class StockParsed(
        val warehouse: String,
        val name: String,
        val unit: String,
        val quantity: Double
    )

    /**
     * الشكل الحقيقي:
     * المخزن الرئيسي | الصنف | الوحدة | الكمية
     *
     * نبحث عن آخر رقم باعتباره الكمية، ثم الوحدة قبله.
     * بهذا لا تتحول أرقام داخل اسم الصنف مثل 2/1هـ أو 4×6
     * إلى كمية.
     */
    private fun parseStockRow(line: String): StockParsed? {
        val tokens = tokenize(line)
        if (tokens.size < 4) return null

        val quantityIndex = tokens.indices.reversed().firstOrNull {
            !isDateOrFooterToken(tokens[it]) && parseQuantity(tokens[it]) != null
        } ?: return null

        if (quantityIndex <= 1) return null

        val quantity = parseQuantity(tokens[quantityIndex]) ?: return null
        val unitIndex = findUnitIndex(tokens, quantityIndex) ?: return null
        if (unitIndex <= 0 || unitIndex >= quantityIndex) return null

        val unit = tokens[unitIndex]
        val prefix = tokens.subList(0, unitIndex)

        val warehouse: String
        val nameStart: Int

        if (
            prefix.size >= 2 &&
            prefix[0] == "المخزن" &&
            prefix[1] == "الرئيسي"
        ) {
            warehouse = "المخزن الرئيسي"
            nameStart = 2
        } else {
            warehouse = prefix.firstOrNull() ?: return null
            nameStart = 1
        }

        val name = tokens.subList(nameStart, unitIndex)
            .joinToString(" ")
            .trim()

        if (name.isBlank() || STOCK_HEADER.containsMatchIn(name)) return null

        return StockParsed(
            warehouse = warehouse,
            name = name,
            unit = unit,
            quantity = abs(quantity)
        )
    }

    private fun findUnitIndex(tokens: List<String>, quantityIndex: Int): Int? {
        val direct = quantityIndex - 1
        if (direct >= 0 && UNITS.contains(tokens[direct])) return direct

        for (i in (quantityIndex - 1) downTo maxOf(0, quantityIndex - 3)) {
            if (UNITS.contains(tokens[i])) return i
        }

        return null
    }

    private fun addProduct(
        parsed: StockParsed,
        session: Long,
        rowNumber: Int,
        page: Int,
        y: Float,
        out: ImportEngine.AnalyzeResult
    ) {
        val t = ArabicNormalizer.process(parsed.name)

        out.products += Product(
            nameRaw = t.raw,
            unit = parsed.unit
        ) to parsed.quantity

        out.totalDebit += parsed.quantity
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
