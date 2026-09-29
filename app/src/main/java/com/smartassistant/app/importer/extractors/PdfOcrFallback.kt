package com.smartassistant.app.importer.extractors

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import java.io.File
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/**
 * OCR fallback for scanned/image-only Arabic PDFs.
 *
 * It is deliberately a fallback: the coordinate/text extractor remains the first
 * and faster path. OCR is used only when the normal PDF text layer yields no
 * importable records.
 */
object PdfOcrFallback {

    private data class Word(
        val page: Int,
        val x: Float,
        val y: Float,
        val right: Float,
        val bottom: Float,
        val text: String,
        val confidence: Float
    )

    private data class Row(
        val page: Int,
        val words: List<Word>
    ) {
        val centerY: Float
            get() = words.map { (it.y + it.bottom) / 2f }.average().toFloat()
    }

    private data class Centers(
        val first: Float,
        val second: Float,
        val third: Float,
        val fourth: Float
    )

    fun parse(
        context: Context,
        file: File,
        kind: ImportKind,
        session: Long
    ): ImportEngine.AnalyzeResult {
        prepareTessdata(context)

        PDDocument.load(file).use { document ->
            require(document.numberOfPages > 0) { "ملف PDF لا يحتوي صفحات." }

            val tess = TessBaseAPI()
            try {
                val dataPath = File(context.filesDir, "tesseract").absolutePath
                check(
                    tess.init(
                        dataPath,
                        "ara",
                        TessBaseAPI.OEM_LSTM_ONLY
                    )
                ) { "تعذر تهيئة محرك OCR العربي." }

                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)

                val renderer = PDFRenderer(document).apply {
                    setSubsamplingAllowed(true)
                }

                val out = ImportEngine.AnalyzeResult()
                var rowNumber = 0

                for (pageIndex in 0 until minOf(document.numberOfPages, 300)) {
                    val bitmap = renderer.renderImageWithDPI(
                        pageIndex,
                        170f,
                        ImageType.RGB
                    )

                    try {
                        val words = recognizePage(tess, bitmap, pageIndex + 1)
                        if (words.isEmpty()) continue

                        val rows = groupRows(words)
                        val centers = detectCenters(rows, kind, bitmap.width.toFloat())
                        val boundaries = floatArrayOf(
                            (centers.first + centers.second) / 2f,
                            (centers.second + centers.third) / 2f,
                            (centers.third + centers.fourth) / 2f
                        )

                        for (row in rows) {
                            val text = rowText(row)
                            if (text.isBlank()) continue
                            if (isHeaderOrFooter(text, kind)) continue

                            when (kind) {
                                ImportKind.CUSTOMER -> {
                                    val parsed = parseCustomer(row, boundaries, bitmap.width)
                                    if (parsed != null) {
                                        rowNumber++
                                        addCustomer(parsed, session, rowNumber, row.page, out)
                                    }
                                }

                                ImportKind.PRODUCT -> {
                                    val parsed = parseProduct(row, boundaries, bitmap.width)
                                    if (parsed != null) {
                                        rowNumber++
                                        addProduct(parsed, session, rowNumber, row.page, out)
                                    }
                                }
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }

                if (out.rows.isEmpty() && out.products.isEmpty()) {
                    throw IllegalStateException(
                        when (kind) {
                            ImportKind.CUSTOMER ->
                                "تم تشغيل OCR العربي، لكن لم يتم العثور على صفوف عملاء قابلة للاستيراد."

                            ImportKind.PRODUCT ->
                                "تم تشغيل OCR العربي، لكن لم يتم العثور على صفوف أصناف ومخزون قابلة للاستيراد."
                        }
                    )
                }

                return out
            } finally {
                tess.recycle()
            }
        }
    }

    private fun prepareTessdata(context: Context) {
        val dir = File(context.filesDir, "tesseract/tessdata")
        val model = File(dir, "ara.traineddata")
        if (model.exists() && model.length() > 1_000_000L) return

        dir.mkdirs()
        context.assets.open("tessdata/ara.traineddata").use { input ->
            model.outputStream().use { output -> input.copyTo(output) }
        }
        check(model.length() > 1_000_000L) {
            "ملف نموذج OCR العربي غير صالح."
        }
    }

    private fun recognizePage(
        tess: TessBaseAPI,
        bitmap: Bitmap,
        page: Int
    ): List<Word> {
        tess.setImage(bitmap)
        tess.getUTF8Text()

        val iterator = tess.getResultIterator() ?: return emptyList()
        val words = mutableListOf<Word>()
        try {
            iterator.begin()
            do {
                val text = clean(iterator.getUTF8Text(PageIteratorLevel.RIL_WORD))
                val rect = iterator.getBoundingRect(PageIteratorLevel.RIL_WORD)
                val confidence = iterator.confidence(PageIteratorLevel.RIL_WORD)
                if (
                    text.length >= 1 &&
                    rect.width() > 1 &&
                    rect.height() > 1 &&
                    confidence >= 25f
                ) {
                    words += Word(
                        page = page,
                        x = rect.left.toFloat(),
                        y = rect.top.toFloat(),
                        right = rect.right.toFloat(),
                        bottom = rect.bottom.toFloat(),
                        text = text,
                        confidence = confidence
                    )
                }
            } while (iterator.next(PageIteratorLevel.RIL_WORD))
        } finally {
            iterator.delete()
        }
        return words
    }

    private fun groupRows(words: List<Word>): List<Row> {
        val rows = mutableListOf<MutableList<Word>>()
        for (word in words.sortedWith(compareBy<Word> { it.page }.thenBy { it.y })) {
            val last = rows.lastOrNull()
            val threshold = max(10f, word.bottom - word.y)
            if (
                last != null &&
                last.first().page == word.page &&
                abs(last.map { it.y + it.bottom }.average() / 2f - word.y - word.bottom / 2f) <= threshold
            ) {
                last += word
            } else {
                rows += mutableListOf(word)
            }
        }
        return rows.map { Row(it.first().page, it.toList()) }
    }

    private fun detectCenters(
        rows: List<Row>,
        kind: ImportKind,
        pageWidth: Float
    ): Centers {
        val header = rows.firstOrNull { row ->
            val t = rowText(row)
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

        val clusters = header?.words
            ?.sortedBy { it.x }
            ?.map { it.text to (it.x + it.right) / 2f }
            .orEmpty()

        fun center(vararg aliases: String): Float? =
            clusters
                .filter { (text, _) ->
                    aliases.any { alias ->
                        clean(text).contains(alias, ignoreCase = true)
                    }
                }
                .map { it.second }
                .average()
                .takeIf { it.isFinite() }
                ?.toFloat()

        return when (kind) {
            ImportKind.CUSTOMER -> Centers(
                first = center("العملة") ?: pageWidth * 0.113f,
                second = center("دائن") ?: pageWidth * 0.236f,
                third = center("مدين") ?: pageWidth * 0.412f,
                fourth = center("الإسم", "الاسم") ?: pageWidth * 0.910f
            )

            ImportKind.PRODUCT -> Centers(
                first = center("الكمية") ?: pageWidth * 0.132f,
                second = center("الوحدة") ?: pageWidth * 0.290f,
                third = center("الصنف") ?: pageWidth * 0.501f,
                fourth = center("اسم", "المخزن") ?: pageWidth * 0.838f
            )
        }
    }

    private data class CustomerParsed(
        val name: String,
        val debit: Double,
        val credit: Double,
        val currency: String,
        val confidence: Int
    )

    private fun parseCustomer(
        row: Row,
        b: FloatArray,
        pageWidth: Int
    ): CustomerParsed? {
        val currency = rtlCell(row, 0f, b[1])
        if (!currency.contains(Regex("ريال|يمن|يمني"), ignoreCase = true)) return null

        val credit = numberIn(row, b[0], b[1]) ?: 0.0
        val debit = numberIn(row, b[1], b[2]) ?: 0.0
        val name = rtlCell(row, b[2], pageWidth.toFloat(), stripNumeric = true)

        if (name.length < 2) return null
        if (isHeaderOrFooter(name, ImportKind.CUSTOMER)) return null

        return CustomerParsed(
            name = name,
            debit = abs(debit),
            credit = abs(credit),
            currency = currency,
            confidence = row.words.map { it.confidence }.average().toInt().coerceIn(25, 99)
        )
    }

    private data class ProductParsed(
        val warehouse: String,
        val name: String,
        val unit: String,
        val quantity: Double,
        val confidence: Int
    )

    private fun parseProduct(
        row: Row,
        b: FloatArray,
        pageWidth: Int
    ): ProductParsed? {
        val quantity = numberIn(row, 0f, b[0])
            ?: numberIn(row, 0f, b[1])
            ?: return null

        val unit = textIn(row, 0f, b[1])
            .replace(Regex("[0-9.,+/×*-]+"), " ")
            .trim()
            .ifBlank { "-" }

        val name = rtlCell(row, b[1], b[2])
        val warehouse = rtlCell(row, b[2], pageWidth.toFloat(), stripNumeric = true)

        if (name.length < 2 || warehouse.isBlank()) return null
        if (isHeaderOrFooter(name, ImportKind.PRODUCT)) return null

        return ProductParsed(
            warehouse = warehouse,
            name = name,
            unit = unit,
            quantity = abs(quantity),
            confidence = row.words.map { it.confidence }.average().toInt().coerceIn(25, 99)
        )
    }

    private fun numberIn(row: Row, minX: Float, maxX: Float): Double? {
        val raw = row.words
            .filter { it.x >= minX && it.x < maxX }
            .sortedBy { it.x }
            .joinToString("") { it.text }
        if (raw.isBlank()) return null

        val normalized = normalizeDigits(raw)
            .replace("٫", ".")
            .replace("٬", ",")
            .replace(" ", "")

        return NumberParser.parse(normalized).value
            ?: normalized.replace(",", ".").toDoubleOrNull()
    }

    private fun textIn(row: Row, minX: Float, maxX: Float): String =
        row.words
            .filter { it.x >= minX && it.x < maxX }
            .sortedByDescending { it.x }
            .joinToString(" ") { it.text }
            .let(::clean)

    private fun rtlCell(
        row: Row,
        minX: Float,
        maxX: Float,
        stripNumeric: Boolean = false
    ): String {
        return row.words
            .filter { it.x >= minX && it.x < maxX }
            .sortedByDescending { it.x }
            .filterNot { stripNumeric && isNumericToken(it.text) }
            .joinToString(" ") { it.text }
            .let(::clean)
    }

    private fun rowText(row: Row): String =
        row.words
            .sortedByDescending { it.x }
            .joinToString(" ") { it.text }
            .let(::clean)

    private fun isNumericToken(text: String): Boolean {
        val normalized = normalizeDigits(text)
        return normalized.isNotBlank() &&
            normalized.all { it.isDigit() || it in ".,-/+" }
    }

    private fun isHeaderOrFooter(text: String, kind: ImportKind): Boolean {
        val t = clean(text)
        if (t.isBlank()) return true
        if (t.contains(Regex("إجمالي|الإجمالي|الاجمالي|المجموع|total"), ignoreCase = true)) return true
        if (t.contains(Regex("\bpage\b|صفحة|تاريخ الطباعة"), ignoreCase = true)) return true
        return when (kind) {
            ImportKind.CUSTOMER ->
                t.contains("الإسم") || t.contains("الاسم") || t.contains("مدين") ||
                    t.contains("دائن") || t.contains("العملة")
            ImportKind.PRODUCT ->
                t.contains("المخزن") || t.contains("الصنف") ||
                    t.contains("الوحدة") || t.contains("الكمية")
        }
    }

    private fun addCustomer(
        parsed: CustomerParsed,
        session: Long,
        rowNumber: Int,
        page: Int,
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
            confidenceScore = parsed.confidence,
            sourceCoordinates = "ocr",
            issues = if (parsed.confidence < 70) "ثقة OCR منخفضة" else null,
            approved = 1
        )
        out.totalCredit += parsed.credit
        out.totalDebit += parsed.debit
    }

    private fun addProduct(
        parsed: ProductParsed,
        session: Long,
        rowNumber: Int,
        page: Int,
        out: ImportEngine.AnalyzeResult
    ) {
        val t = ArabicNormalizer.process(parsed.name)
        out.products += Product(nameRaw = t.raw, unit = parsed.unit) to parsed.quantity
        out.rows += ImportRawRow(
            sessionId = session,
            pageNumber = page,
            rowNumber = rowNumber,
            nameRaw = t.raw,
            nameDisplay = t.display,
            nameNormalized = t.normalized,
            credit = 0.0,
            debit = parsed.quantity,
            currency = parsed.unit,
            net = parsed.quantity,
            status = "VALID",
            confidenceScore = parsed.confidence,
            sourceCoordinates = "ocr",
            issues = if (parsed.confidence < 70) "ثقة OCR منخفضة" else null,
            approved = 1
        )
        out.totalDebit += parsed.quantity
    }

    private fun clean(text: String): String =
        Normalizer.normalize(
            text
                .replace('\u00A0', ' ')
                .replace('\u0640'.toString(), ""),
            Normalizer.Form.NFKC
        )
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeDigits(text: String): String =
        text.map { c ->
            when (c) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                '۰' -> '0'; '۱' -> '1'; '۲' -> '2'; '۳' -> '3'; '۴' -> '4'
                '۵' -> '5'; '۶' -> '6'; '۷' -> '7'; '۸' -> '8'; '۹' -> '9'
                else -> c
            }
        }.joinToString("")
}
