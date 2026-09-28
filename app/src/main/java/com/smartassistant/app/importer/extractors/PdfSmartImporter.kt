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
 * محرك PDF مبني على إحداثيات الخلايا الفعلية في التقرير.
 *
 * PDFBox قد يعيد النص العربي على أكثر من سطر منطقي، أو يخلط ترتيب
 * الأعمدة بسبب RTL. لذلك لا نعتمد على getText()/writeString().
 *
 * التقارير المستهدفة:
 * 1) العملاء: الاسم | مدين | دائن | العملة
 * 2) المخزون: اسم المخزن | الصنف | الوحدة | الكمية
 */
object PdfSmartImporter {

    private data class PositionedChar(
        val page: Int,
        val x: Float,
        val y: Float,
        val text: String
    )

    private data class VisualRow(
        val page: Int,
        val y: Float,
        val chars: List<PositionedChar>
    )

    private class CoordinateStripper : PDFTextStripper() {
        val chars = mutableListOf<PositionedChar>()

        init {
            sortByPosition = true
            wordSeparator = " "
            lineSeparator = "\n"
        }

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            if (unicode.isBlank()) return

            chars += PositionedChar(
                page = currentPageNo,
                x = text.xDirAdj,
                y = text.yDirAdj,
                text = unicode
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

    // الوحدات الموجودة فعلياً في تقرير المخزون المرفوع، مع قبول "-" إذا كان
    // التقرير يترك خانة الوحدة بشرطة بدلاً من حذف الصف.
    private val UNITS = setOf(
        "حبة", "حبه", "باكت", "كيس", "علبة", "علب",
        "لفة", "لفه", "ل", "ك", "كيلو", "كجم", "جرام",
        "متر", "م", "قطعة", "قطعه", "صندوق", "كرتون",
        "جالون", "برميل", "طقم", "زوج", "شدة", "درزن",
        "قطمة", "دبة", "-"
    )

    private fun clean(text: String): String =
        Normalizer.normalize(
            text
                .replace('\u00A0', ' ')
                .replace('\r', ' ')
                .replace('\u0640', ''),
            Normalizer.Form.NFKC
        )
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * PDFBox يعيد بعض علامات الترقيم RTL بترتيب بصري معكوس:
     * درويش)البنشر( بدلاً من درويش(البنشر).
     */
    private fun normalizeRtlPunctuation(text: String): String =
        text
            .replace('(', '\uE000')
            .replace(')', '(')
            .replace('\uE000', ')')

    private fun isDate(text: String): Boolean =
        DATE.matches(clean(text))

    private fun isNumericChar(text: String): Boolean =
        text.length == 1 && (text[0].isDigit() || text[0] in ".,-")

    private fun isNumericToken(text: String): Boolean {
        val s = clean(text)
        if (s.isBlank()) return false
        return s.all { it.isDigit() || it in ".,-" }
    }

    private fun groupVisualRows(chars: List<PositionedChar>): List<VisualRow> {
        if (chars.isEmpty()) return emptyList()

        val rows = mutableListOf<MutableList<PositionedChar>>()

        for (ch in chars.sortedWith(compareBy<PositionedChar> { it.page }.thenBy { it.y })) {
            val last = rows.lastOrNull()
            if (
                last != null &&
                last.first().page == ch.page &&
                abs(last.map { it.y }.average().toFloat() - ch.y) <= 3.5f
            ) {
                last += ch
            } else {
                rows += mutableListOf(ch)
            }
        }

        return rows.map { row ->
            VisualRow(
                page = row.first().page,
                y = row.map { it.y }.average().toFloat(),
                chars = row.toList()
            )
        }
    }

    private fun rtlText(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float
    ): String {
        return normalizeRtlPunctuation(
            chars
                .filter { it.x >= minX && it.x < maxX }
                .sortedByDescending { it.x }
                .joinToString("") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
        )
    }

    private fun numericText(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float
    ): String {
        return chars
            .filter {
                it.x >= minX &&
                    it.x < maxX &&
                    isNumericChar(it.text)
            }
            .sortedBy { it.x }
            .joinToString("") { it.text }
            .trim()
    }

    private fun textWithoutNumericChars(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float
    ): String {
        return normalizeRtlPunctuation(
            chars
                .filter {
                    it.x >= minX &&
                        it.x < maxX &&
                        !isNumericChar(it.text)
                }
                .sortedByDescending { it.x }
                .joinToString("") { it.text }
                .replace(Regex("\\s+"), " ")
                .trim()
        )
    }

    /**
     * 22,275 = 22.275
     * 0,802  = 0.802
     * بينما القيم ذات النقطة تبقى عشرية طبيعية.
     */
    private fun parseQuantity(raw: String): Double? {
        val s = NumberParser.normalize(raw).replace(" ", "").trim()
        if (s.isBlank()) return null

        if (s.matches(Regex("\\d+,\\d{1,3}"))) {
            return s.replace(',', '.').toDoubleOrNull()
        }

        return NumberParser.parse(s).value
    }

    private fun isDateOrFooter(text: String): Boolean =
        isDate(text) || text.contains("2026-08-31")

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

            val stripper = CoordinateStripper().apply {
                startPage = 1
                endPage = minOf(document.numberOfPages, 300)
            }

            stripper.getText(document)

            val rows = groupVisualRows(stripper.chars)
            if (rows.isEmpty()) {
                throw IllegalStateException("لم يتم استخراج أي نص قابل للقراءة من PDF.")
            }

            var rowNumber = 0

            for (row in rows) {
                val rowText = clean(
                    row.chars.sortedBy { it.x }.joinToString(" ") { it.text }
                )

                if (rowText.isBlank()) continue
                if (shopName != null && shopName.isNotBlank() && rowText.contains(shopName)) continue
                if (TOTAL_HEADER.containsMatchIn(rowText)) continue

                when (kind) {
                    ImportKind.CUSTOMER -> {
                        if (CUSTOMER_HEADER.containsMatchIn(rowText)) continue

                        val parsed = parseCustomerRow(row) ?: continue
                        rowNumber++

                        addCustomer(
                            parsed = parsed,
                            session = session,
                            rowNumber = rowNumber,
                            page = row.page,
                            y = row.y,
                            out = out
                        )
                    }

                    ImportKind.PRODUCT -> {
                        if (STOCK_HEADER.containsMatchIn(rowText)) continue

                        val parsed = parseStockRow(row) ?: continue
                        rowNumber++

                        addProduct(
                            parsed = parsed,
                            session = session,
                            rowNumber = rowNumber,
                            page = row.page,
                            y = row.y,
                            out = out
                        )
                    }
                }
            }
        }

        if (out.rows.isEmpty() && out.products.isEmpty()) {
            throw IllegalStateException(
                when (kind) {
                    ImportKind.CUSTOMER ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف عملاء. تم فحص خلايا الاسم والمدين والدائن والعملة بالإحداثيات."

                    ImportKind.PRODUCT ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف مخزون. تم فحص خلايا المخزن والصنف والوحدة والكمية بالإحداثيات."
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
     * الإحداثيات الفعلية في تقرير العملاء:
     * العملة 0..105، الدائن 105..195، المدين 195..305، الاسم 305..MAX.
     */
    private fun parseCustomerRow(row: VisualRow): CustomerParsed? {
        val chars = row.chars

        val currency = rtlText(chars, 0f, 105f)
        if (!currency.contains("ريال", ignoreCase = true)) return null

        val creditRaw = numericText(chars, 105f, 195f)
        val debitRaw = numericText(chars, 195f, 305f)

        if (creditRaw.isBlank() || debitRaw.isBlank()) return null
        if (!isNumericToken(creditRaw) || !isNumericToken(debitRaw)) return null

        val credit = NumberParser.parse(creditRaw).value ?: return null
        val debit = NumberParser.parse(debitRaw).value ?: return null

        val name = textWithoutNumericChars(chars, 305f, Float.MAX_VALUE)
        if (name.isBlank()) return null
        if (CUSTOMER_HEADER.containsMatchIn(name)) return null
        if (TOTAL_HEADER.containsMatchIn(name)) return null
        if (isDateOrFooter(name)) return null

        return CustomerParsed(
            name = clean(name),
            debit = abs(debit),
            credit = abs(credit),
            currency = clean(currency)
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
            confidenceScore = 99,
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
     * الإحداثيات الفعلية في تقرير المخزون:
     * الكمية 0..150، الوحدة 150..195، الصنف 195..450، المخزن 450..MAX.
     */
    private fun parseStockRow(row: VisualRow): StockParsed? {
        val chars = row.chars

        val quantityRaw = numericText(chars, 0f, 150f)
        if (quantityRaw.isBlank()) return null
        if (!isNumericToken(quantityRaw)) return null

        val quantity = parseQuantity(quantityRaw) ?: return null

        val unit = rtlText(chars, 150f, 195f)
        if (unit.isBlank()) return null

        val normalizedUnit = clean(unit)
        if (normalizedUnit !in UNITS) return null

        val name = rtlText(chars, 195f, 450f)
        if (name.isBlank()) return null
        if (STOCK_HEADER.containsMatchIn(name)) return null
        if (TOTAL_HEADER.containsMatchIn(name)) return null
        if (isDateOrFooter(name)) return null

        val warehouse = rtlText(chars, 450f, Float.MAX_VALUE)
        if (warehouse.isBlank()) return null

        return StockParsed(
            warehouse = warehouse,
            name = clean(name),
            unit = normalizedUnit,
            quantity = abs(quantity)
        )
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
