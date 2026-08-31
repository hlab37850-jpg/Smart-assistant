package com.smartassistant.app.importer

import android.database.sqlite.SQLiteDatabase
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.ImportError
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.SessionStatus
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.smartassistant.app.util.Csv
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.security.MessageDigest

/**
 * محرك الاستيراد الحالي — يعمل مع Entities الجديدة.
 * Pipeline الكامل (WordBox + Layout Analyzer) سيأتي في الدفعة 2.
 */
object ImportEngine {

    private val SKIP = Regex("اجمالي|المجموع|total|صفحة|page|تاريخ الطباعة|عنوان المحل|هاتف المحل", RegexOption.IGNORE_CASE)
    private val CREDIT_WORD = Regex("(?:^|\\s)(له|دائن)(?:\\s|$)")
    private val DEBIT_WORD = Regex("(?:^|\\s)(عليه|مدين)(?:\\s|$)")

    fun computeHash(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun detectType(f: File, fallback: String): String = runCatching {
        val buf = ByteArray(16)
        f.inputStream().use { it.read(buf) }
        when {
            buf[0] == '%'.code.toByte() && buf[1] == 'P'.code.toByte() && buf[2] == 'D'.code.toByte() -> "pdf"
            buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte() -> "xlsx"
            String(buf, 0, 6) == "SQLite" -> "db"
            else -> fallback
        }
    }.getOrDefault(fallback)

    private fun numOf(r: List<String>, col: Int): NumberParser.Result =
        if (col >= 0) NumberParser.parse(r.getOrElse(col) { "" }) else NumberParser.Result(null, "", 0)

    private fun lastNum(r: List<String>, exclude: String): NumberParser.Result =
        r.lastOrNull { c -> c != exclude && NumberParser.parse(c).value != null }
            ?.let { NumberParser.parse(it) } ?: NumberParser.Result(null, "", 0)

    fun analyze(
        rows: List<List<String>>,
        existing: List<Customer>,
        kind: ImportKind,
        session: Long,
        page: Int = 0
    ): AnalyzeResult {
        if (rows.isEmpty()) return AnalyzeResult()
        var header = -1
        var nameCol = 0; var phoneCol = -1; var balCol = -1; var qtyCol = -1
        var codeCol = -1; var debitCol = -1; var creditCol = -1
        for ((i, r) in rows.withIndex()) {
            val joined = r.joinToString(" ")
            if (joined.contains(Regex("اسم|عميل|صنف|هاتف|رصيد|كميه|كمية|مدين|دائن"))) {
                header = i
                nameCol = r.indexOfFirst { it.contains(Regex("اسم|عميل|صنف")) }.coerceAtLeast(0)
                phoneCol = r.indexOfFirst { it.contains(Regex("هاتف|جوال|phone|mobile")) }
                balCol = r.indexOfFirst { it.contains(Regex("رصيد|balance")) }
                qtyCol = r.indexOfFirst { it.contains(Regex("كميه|كمية|qty")) }
                codeCol = r.indexOfFirst { it.contains(Regex("كود|code")) }
                debitCol = r.indexOfFirst { it.contains(Regex("مدين|عليه")) }
                creditCol = r.indexOfFirst { CREDIT_WORD.containsMatchIn(it) }
                break
            }
            if (i > 10) break
        }
        val out = AnalyzeResult()
        var rowNum = 0
        rows.drop(header + 1).forEach { r ->
            rowNum++
            val joined = r.joinToString(" ")
            if (joined.isBlank() || SKIP.containsMatchIn(joined)) { out.ignored++; return@forEach }
            val rawName = r.getOrElse(nameCol) { "" }
            if (rawName.isBlank()) { out.ignored++; return@forEach }

            val nameTriple = ArabicNormalizer.process(rawName)

            if (kind == ImportKind.PRODUCT) {
                val qty = numOf(r, qtyCol).value ?: lastNum(r, rawName).value ?: 0.0
                val code = if (codeCol >= 0) r.getOrElse(codeCol) { "" }.trim().ifEmpty { null } else null
                out.products += Product(nameRaw = nameTriple.raw, code = code) to qty
                return@forEach
            }

            val creditV: Double; val debitV: Double
            when {
                debitCol >= 0 && creditCol >= 0 -> {
                    debitV = numOf(r, debitCol).value ?: 0.0
                    creditV = numOf(r, creditCol).value ?: 0.0
                }
                balCol >= 0 -> {
                    val parsed = numOf(r, balCol)
                    val v = parsed.value ?: 0.0
                    val abs = kotlin.math.abs(v)
                    when {
                        CREDIT_WORD.containsMatchIn(joined) -> { creditV = abs; debitV = 0.0 }
                        DEBIT_WORD.containsMatchIn(joined) -> { debitV = abs; creditV = 0.0 }
                        v >= 0 -> { debitV = abs; creditV = 0.0 }
                        else -> { creditV = abs; debitV = 0.0 }
                    }
                }
                else -> {
                    val v = lastNum(r, rawName).value ?: 0.0
                    val abs = kotlin.math.abs(v)
                    when {
                        CREDIT_WORD.containsMatchIn(joined) -> { creditV = abs; debitV = 0.0 }
                        DEBIT_WORD.containsMatchIn(joined) -> { debitV = abs; creditV = 0.0 }
                        v >= 0 -> { debitV = abs; creditV = 0.0 }
                        else -> { creditV = abs; debitV = 0.0 }
                    }
                }
            }
            val net = debitV - creditV
            val phone = if (phoneCol >= 0) r.getOrElse(phoneCol) { "" }.replace(Regex("[^0-9+]"),"").ifEmpty { null } else null
            val code = if (codeCol >= 0) r.getOrElse(codeCol) { "" }.trim().ifEmpty { null } else null

            out.rows += ImportRawRow(
                sessionId = session,
                pageNumber = page,
                rowNumber = rowNum,
                nameRaw = nameTriple.raw,
                nameDisplay = nameTriple.display,
                nameNormalized = nameTriple.normalized,
                credit = creditV,
                debit = debitV,
                currency = null,
                net = net,
                status = "VALID",
                confidenceScore = 95,
                sourceCoordinates = "p$page:r$rowNum",
                issues = null,
                approved = 1
            )
            out.totalCredit += creditV
            out.totalDebit += debitV
        }
        return out
    }

    fun fromCsv(text: String, kind: ImportKind, session: Long): AnalyzeResult =
        analyze(Csv.parse(text), emptyList(), kind, session)

    fun fromXlsx(file: File, kind: ImportKind, session: Long): AnalyzeResult {
        val sheets = XlsxReader.read(file)
        if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() })
            throw IllegalStateException("ملف Excel فارغ أو غير مقروء.")
        return analyze(sheets.flatMap { it.rows }, emptyList(), kind, session)
    }

    fun fromPdf(file: File, shopName: String?, kind: ImportKind, session: Long): AnalyzeResult {
        val rows = mutableListOf<List<String>>()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.setEndPage(minOf(doc.numberOfPages, 200))
            val text = stripper.getText(doc)
            if (text.isBlank())
                throw IllegalStateException("الملف لا يحتوي نصاً قابلاً للاستخراج.")
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.length < 2) return@forEach
                if (shopName != null && trimmed.contains(shopName)) return@forEach
                if (SKIP.containsMatchIn(trimmed)) return@forEach
                val cells = trimmed.split(Regex("\\s{2,}|\t")).map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.isEmpty()) return@forEach
                if (cells.size == 1) {
                    if (NumberParser.parse(cells[0]).value == null && cells[0].length > 2)
                        rows += listOf(cells[0], "")
                    return@forEach
                }
                rows += cells
            }
        }
        if (rows.isEmpty()) throw IllegalStateException("لم يُعثر على بيانات داخل PDF.")
        return analyze(rows, emptyList(), kind, session)
    }

    fun fromDb(file: File, kind: ImportKind, session: Long): AnalyzeResult {
        val combined = mutableListOf<List<String>>()
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
            for (t in tables) {
                if (t.contains(Regex("sqlite|android|room|master", RegexOption.IGNORE_CASE))) continue
                val cols = mutableListOf<String>()
                db.rawQuery("PRAGMA table_info($t)", null).use { c ->
                    while (c.moveToNext()) cols += c.getString(1)
                }
                val isCust = cols.any { it.contains(Regex("name|اسم", RegexOption.IGNORE_CASE)) } &&
                    cols.any { it.contains(Regex("phone|mobile|هاتف|balance|رصيد", RegexOption.IGNORE_CASE)) }
                val isProd = cols.any { it.contains(Regex("qty|quantity|كمية", RegexOption.IGNORE_CASE)) }
                val wanted = if (kind == ImportKind.CUSTOMER) isCust else isProd
                if (!wanted) continue
                combined += cols
                db.rawQuery("SELECT * FROM $t LIMIT 5000", null).use { c ->
                    while (c.moveToNext()) {
                        combined += (0 until c.columnCount).map { i -> c.getString(i) ?: "" }
                    }
                }
            }
        }
        if (combined.isEmpty()) throw IllegalStateException("لم يُعثر على جداول داخل قاعدة البيانات.")
        return analyze(combined, emptyList(), kind, session)
    }

    suspend fun apply(repo: MainRepo, session: Long, rows: List<ImportRawRow>) {
        repo.db.runInTransaction {
            rows.forEach { row ->
                if (row.nameNormalized.isBlank()) return@forEach
                val existing = repo.db.customerDao().byNormalizedName(row.nameNormalized)
                val net = row.debit - row.credit
                if (existing != null) {
                    repo.db.customerDao().update(existing.copy(
                        balance = net,
                        debit = row.debit,
                        credit = row.credit,
                        updatedAt = System.currentTimeMillis(),
                        sourcePage = row.pageNumber,
                        importSessionId = session
                    ))
                } else {
                    repo.db.customerDao().insert(Customer(
                        name = row.nameDisplay,
                        nameNormalized = row.nameNormalized,
                        balance = net,
                        rawBalance = row.nameRaw,
                        debit = row.debit,
                        credit = row.credit,
                        sourcePage = row.pageNumber,
                        importSessionId = session
                    ))
                }
            }
        }
        val sessionEntity = repo.db.importDao().sessionById(session)
        if (sessionEntity != null) {
            repo.db.importDao().updateSession(sessionEntity.copy(
                status = SessionStatus.COMPLETED.name,
                finishedAt = System.currentTimeMillis(),
                validCount = rows.size,
                totalCredit = rows.sumOf { it.credit },
                totalDebit = rows.sumOf { it.debit },
                net = rows.sumOf { it.debit - it.credit }
            ))
        }
    }

    data class AnalyzeResult(
        val rows: MutableList<ImportRawRow> = mutableListOf(),
        val products: MutableList<Pair<Product, Double>> = mutableListOf(),
        var totalCredit: Double = 0.0,
        var totalDebit: Double = 0.0,
        var ignored: Int = 0
    )
}
