package com.smartassistant.app.importer.extractors

import android.content.Context

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
 * محرك PDF متعدد الطبقات:
 * 1) استخراج النص الأصلي مع إحداثيات X/Y.
 * 2) إعادة بناء الصفوف بصرياً.
 * 3) اكتشاف رؤوس الأعمدة ومعايرة الحدود تلقائياً.
 * 4) تحليل كل خلية مستقلة عن ترتيب النص في PDF.
 * 5) قواعد متخصصة للأرقام وRTL والفواصل العشرية.
 * 6) عدم إسقاط الصف لمجرد أن عموداً رقمياً قيمته صفر/غير مرسوم.
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

    private data class HeaderCenters(
        val first: Float,
        val second: Float,
        val third: Float,
        val fourth: Float
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

    private val UNITS = setOf(
        "حبة", "حبه", "باكت", "باكيت", "كيس", "علبة", "علب",
        "لفة", "لفه", "كيلو", "كجم", "جرام", "متر",
        "قطعة", "قطعه", "صندوق", "كرتون", "جالون", "برميل",
        "طقم", "زوج", "شدة", "درزن", "قطمة", "دبة",
        "لتر", "ل", "ك", "-"
    )

    private fun clean(text: String): String =
        Normalizer.normalize(
            text
                .replace('\u00A0', ' ')
                .replace('\r', ' ')
                .replace("\u0640", ""),
            Normalizer.Form.NFKC
        )
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeRtlPunctuation(text: String): String =
        text
            .replace('(', '\uE000')
            .replace(')', '(')
            .replace('\uE000', ')')

    private fun isDate(text: String): Boolean = DATE.matches(clean(text))

    private fun isNumericLikeChar(text: String): Boolean =
        text.length == 1 && (text[0].isDigit() || text[0] in ".,-/×*")

    private fun rowText(row: VisualRow): String =
        clean(row.chars.sortedBy { it.x }.joinToString(" ") { it.text })

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

    private fun headerClusters(row: VisualRow): List<Pair<String, Float>> {
        val sorted = row.chars.sortedBy { it.x }
        if (sorted.isEmpty()) return emptyList()

        val groups = mutableListOf<MutableList<PositionedChar>>()
        for (ch in sorted) {
            val last = groups.lastOrNull()
            if (last == null || ch.x - last.last().x <= 18f) {
                if (last == null) groups += mutableListOf(ch) else last += ch
            } else {
                groups += mutableListOf(ch)
            }
        }

        return groups.map { g ->
            val text = normalizeRtlPunctuation(
                g.sortedByDescending { it.x }.joinToString("") { it.text }
            )
            text to ((g.minOf { it.x } + g.maxOf { it.x }) / 2f)
        }
    }

    private fun findCenter(
        clusters: List<Pair<String, Float>>,
        aliases: List<String>
    ): Float? =
        clusters
            .filter { (text, _) ->
                aliases.any { alias -> clean(text).contains(alias, ignoreCase = true) }
            }
            .map { it.second }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()

    private fun detectCenters(
        rows: List<VisualRow>,
        kind: ImportKind,
        pageWidth: Float
    ): HeaderCenters {
        val header = rows.firstOrNull { r ->
            val t = rowText(r)
            when (kind) {
                ImportKind.CUSTOMER ->
                    t.contains("العملة") &&
                        t.contains(Regex("مدين|دائن")) &&
                        t.contains(Regex("الإسم|الاسم"))

                ImportKind.PRODUCT ->
                    t.contains("الكمية") &&
                        t.contains("الوحدة") &&
                        t.contains("الصنف") &&
                        t.contains("المخزن")
            }
        }

        val clusters = header?.let(::headerClusters).orEmpty()

        return when (kind) {
            ImportKind.CUSTOMER -> HeaderCenters(
                first = findCenter(clusters, listOf("العملة")) ?: pageWidth * 0.113f,
                second = findCenter(clusters, listOf("دائن")) ?: pageWidth * 0.236f,
                third = findCenter(clusters, listOf("مدين")) ?: pageWidth * 0.412f,
                fourth = findCenter(clusters, listOf("الإسم", "الاسم")) ?: pageWidth * 0.910f
            )

            ImportKind.PRODUCT -> HeaderCenters(
                first = findCenter(clusters, listOf("الكمية")) ?: pageWidth * 0.132f,
                second = findCenter(clusters, listOf("الوحدة")) ?: pageWidth * 0.290f,
                third = findCenter(clusters, listOf("الصنف")) ?: pageWidth * 0.501f,
                fourth = findCenter(clusters, listOf("اسم", "المخزن")) ?: pageWidth * 0.838f
            )
        }
    }

    private fun boundaries(c: HeaderCenters): FloatArray = floatArrayOf(
        (c.first + c.second) / 2f,
        (c.second + c.third) / 2f,
        (c.third + c.fourth) / 2f
    )

    private fun rtlCell(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float,
        stripNumeric: Boolean = false
    ): String {
        val ordered = chars
            .filter { it.x >= minX && it.x < maxX }
            .sortedByDescending { it.x }

        val raw = ordered
            .filterNot { stripNumeric && isNumericLikeChar(it.text) }
            .joinToString("") { it.text }

        return normalizeRtlPunctuation(clean(raw))
    }

    private fun numericCell(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float
    ): String {
        return chars
            .filter {
                it.x >= minX &&
                    it.x < maxX &&
                    it.text.length == 1 &&
                    (it.text[0].isDigit() || it.text[0] in ".,-+")
            }
            .sortedBy { it.x }
            .joinToString("") { it.text }
            .trim()
    }

    private fun parseNumber(raw: String): Double? =
        NumberParser.parse(clean(raw)).value

    private fun parseQuantity(raw: String): Double? {
        val s = clean(raw).replace(" ", "")
        if (s.isBlank()) return null

        val normalized = s.replace(',', '.')
        return normalized.toDoubleOrNull() ?: NumberParser.parse(s).value
    }

    private fun extractUnit(
        chars: List<PositionedChar>,
        minX: Float,
        maxX: Float
    ): String {
        val raw = rtlCell(chars, minX, maxX, stripNumeric = true)
            .replace(Regex("[/×*.,+\\-]+"), " ")
            .trim()

        val exact = raw.split(Regex("\\s+"))
            .firstOrNull { it in UNITS }
        if (exact != null) return exact

        return UNITS.firstOrNull { unit ->
            raw.contains(unit, ignoreCase = true)
        } ?: ""
    }

    private fun isDateOrFooter(text: String): Boolean =
        isDate(text) || text.contains("2026-08-31")

    fun parse(
        context: Context,
        file: File,
        shopName: String?,
        kind: ImportKind,
        session: Long
    ): ImportEngine.AnalyzeResult {
        return runCatching {
            parseTextLayer(file, shopName, kind, session)
        }.getOrElse {
            PdfOcrFallback.parse(context, file, kind, session)
        }
    }

    private fun parseTextLayer(
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

            val pageWidth = document.getPage(0).mediaBox.width
            val centers = detectCenters(rows, kind, pageWidth)
            val b = boundaries(centers)

            var rowNumber = 0

            for (row in rows) {
                val text = rowText(row)
                if (text.isBlank()) continue
                if (shopName != null && shopName.isNotBlank() && text.contains(shopName)) continue
                if (TOTAL_HEADER.containsMatchIn(text)) continue

                when (kind) {
                    ImportKind.CUSTOMER -> {
                        if (CUSTOMER_HEADER.containsMatchIn(text)) continue
                        val parsed = parseCustomerRow(row, b) ?: continue
                        rowNumber++
                        addCustomer(parsed, session, rowNumber, row.page, row.y, out)
                    }

                    ImportKind.PRODUCT -> {
                        if (STOCK_HEADER.containsMatchIn(text)) continue
                        val parsed = parseStockRow(row, b) ?: continue
                        rowNumber++
                        addProduct(parsed, session, rowNumber, row.page, row.y, out)
                    }
                }
            }
        }

        if (out.rows.isEmpty() && out.products.isEmpty()) {
            throw IllegalStateException(
                when (kind) {
                    ImportKind.CUSTOMER ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف عملاء قابلة للاستيراد."

                    ImportKind.PRODUCT ->
                        "تم فتح PDF، لكن لم يتم العثور على صفوف أصناف ومخزون قابلة للاستيراد."
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

    private fun parseCustomerRow(
        row: VisualRow,
        b: FloatArray
    ): CustomerParsed? {
        val currency = rtlCell(row.chars, 0f, b[1], stripNumeric = true)
        if (!currency.contains("ريال", ignoreCase = true)) return null

        val creditRaw = numericCell(row.chars, b[0], b[1])
        val debitRaw = numericCell(row.chars, b[1], b[2])

        val credit = if (creditRaw.isBlank()) 0.0 else parseNumber(creditRaw) ?: return null
        val debit = if (debitRaw.isBlank()) 0.0 else parseNumber(debitRaw) ?: return null

        val name = rtlCell(row.chars, b[2], Float.MAX_VALUE, stripNumeric = true)
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
        val confidence = when {
            parsed.name.length < 2 -> 70
            parsed.debit == 0.0 && parsed.credit == 0.0 -> 75
            else -> 99
        }

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
            status = if (confidence >= 90) "VALID" else "WARNING",
            confidenceScore = confidence,
            sourceCoordinates = "pdf:p" + page + ":y" + y,
            issues = if (confidence < 90) "رصيد غير ظاهر في الأعمدة الرقمية" else null,
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

    private fun parseStockRow(
        row: VisualRow,
        b: FloatArray
    ): StockParsed? {
        val quantityRaw = numericCell(row.chars, 0f, b[1])
        if (quantityRaw.isBlank()) return null

        val quantityLeft = numericCell(row.chars, 0f, b[0])
        val quantity = parseQuantity(
            if (quantityLeft.isNotBlank()) quantityLeft else quantityRaw
        ) ?: return null

        val unit = extractUnit(row.chars, 0f, b[1]).ifBlank { "-" }

        val name = rtlCell(row.chars, b[1], b[2], stripNumeric = false)
        if (name.isBlank()) return null
        if (STOCK_HEADER.containsMatchIn(name)) return null
        if (TOTAL_HEADER.containsMatchIn(name)) return null
        if (isDateOrFooter(name)) return null

        val warehouse = rtlCell(row.chars, b[2], Float.MAX_VALUE, stripNumeric = true)
        if (warehouse.isBlank()) return null

        return StockParsed(
            warehouse = clean(warehouse),
            name = clean(name),
            unit = unit,
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
        out.products += Product(nameRaw = t.raw, unit = parsed.unit) to parsed.quantity
        out.totalDebit += parsed.quantity
    }
}

suspend fun deleteSessionData(repo: MainRepo, sid: Long) {
    repo.db.customerDao().deleteBySession(sid)
    runCatching { repo.db.customerDao().deleteUnlinked() }
    repo.db.importDao().deleteRows(sid)
    repo.db.importDao().deleteIssues(sid)
    repo.db.importDao().deleteSession(sid)
}
