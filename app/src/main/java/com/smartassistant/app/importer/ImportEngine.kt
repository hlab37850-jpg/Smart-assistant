package com.smartassistant.app.importer

import android.database.sqlite.SQLiteDatabase
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.ImportRecord
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.util.Csv
import com.smartassistant.app.util.DataPreservation
import com.smartassistant.app.util.Matching
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

data class ImportResult(
    val addCustomers: List<Customer> = emptyList(),
    val updateCustomers: List<Customer> = emptyList(),
    val addProducts: List<Pair<Product, Double>> = emptyList(),
    val review: List<String> = emptyList(),
    val ignored: Int = 0,
)

object ImportEngine {

    private val SKIP = Regex("اجمالي|المجموع|total|صفحة|page|تاريخ الطباعة|عنوان المحل|هاتف المحل", RegexOption.IGNORE_CASE)
    private val DEBIT_WORD = Regex("(?:^|\\s)(عليه|مدين)(?:\\s|$)")
    private val CREDIT_WORD = Regex("(?:^|\\s)(له|دائن)(?:\\s|$)")

    /** كشف نوع الملف من المحتوى (لا يعتمد على الاسم) */
    fun detectType(f: File, fallback: String): String = try {
        val buf = ByteArray(16)
        f.inputStream().use { it.read(buf) }
        when {
            buf[0] == '%'.code.toByte() && buf[1] == 'P'.code.toByte() && buf[2] == 'D'.code.toByte() -> "pdf"
            buf[0] == 'P'.code.toByte() && buf[1] == 'K'.code.toByte() -> "xlsx"
            String(buf, 0, 6) == "SQLite" -> "db"
            else -> fallback
        }
    } catch (e: Exception) { fallback }

    private fun numOf(r: List<String>, col: Int): Double? =
        if (col >= 0) DataPreservation.parseAmount(r.getOrElse(col) { "" }).value else null

    private fun lastNumber(r: List<String>, exclude: String): Double? =
        r.lastOrNull { c -> c != exclude && DataPreservation.parseAmount(c).value != null }
            ?.let { DataPreservation.parseAmount(it).value }

    private fun rowHas(r: List<String>, rx: Regex) = r.any { rx.containsMatchIn(it) }

    /** kind: CUSTOMER / PRODUCT / null = تلقائي */
    fun analyze(rows: List<List<String>>, existing: List<Customer>, kind: String?): ImportResult {
        if (rows.isEmpty()) return ImportResult()
        var header = -1
        var nameCol = 0; var phoneCol = -1; var balCol = -1; var qtyCol = -1; var codeCol = -1
        var debitCol = -1; var creditCol = -1
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
        val dataRows = rows.drop(header + 1)
        val addC = mutableListOf<Customer>(); val upC = mutableListOf<Customer>()
        val addP = mutableListOf<Pair<Product, Double>>(); val review = mutableListOf<String>()
        var ignored = 0

        for (r in dataRows) {
            val joined = r.joinToString(" ")
            if (joined.isBlank() || SKIP.containsMatchIn(joined)) { ignored++; continue }
            val name = DataPreservation.keepRaw(r.getOrElse(nameCol) { "" })
            if (name.isEmpty() || DataPreservation.parseAmount(name).value != null) { review += joined; continue }
            val phone = if (phoneCol >= 0) DataPreservation.parsePhone(r.getOrElse(phoneCol) { null }) else null
            val code = if (codeCol >= 0) DataPreservation.keepRaw(r.getOrElse(codeCol) { "" }).ifEmpty { null } else null

            val isProduct = if (kind != null) kind == "PRODUCT"
                            else (qtyCol >= 0 && balCol < 0 && debitCol < 0)

            if (isProduct) {
                val q = numOf(r, qtyCol) ?: lastNumber(r, name) ?: 0.0
                addP += Product(nameRaw = name, code = code) to q
                continue
            }

            // ===== حساب رصيد العميل بكل الحالات =====
            val balance: Double
            var rawBal: String?
            when {
                debitCol >= 0 && creditCol >= 0 -> {
                    balance = (numOf(r, debitCol) ?: 0.0) - (numOf(r, creditCol) ?: 0.0)
                    rawBal = joined
                }
                balCol >= 0 -> {
                    val cell = r.getOrElse(balCol) { "" }
                    val parsed = DataPreservation.parseAmount(cell)
                    if (parsed.value == null && cell.isNotBlank()) { review += joined; continue }
                    var v = parsed.value ?: 0.0
                    if (rowHas(r, CREDIT_WORD)) v = -kotlin.math.abs(v)
                    if (rowHas(r, DEBIT_WORD)) v = kotlin.math.abs(v)
                    balance = v; rawBal = cell
                }
                else -> {
                    val lastNum = lastNumber(r, name)
                    var v = lastNum ?: 0.0
                    if (lastNum != null) {
                        if (rowHas(r, CREDIT_WORD)) v = -kotlin.math.abs(v)
                        if (rowHas(r, DEBIT_WORD)) v = kotlin.math.abs(v)
                    }
                    balance = v; rawBal = lastNum?.toString()
                }
            }
            if (kotlin.math.abs(balance) > 1_000_000_000) { review += joined; continue }

            val match = existing
                .map { it to Matching.confidence(it, null, phone, code, name) }
                .maxByOrNull { it.second }
            when {
                match != null && match.second >= 0.8 ->
                    upC += match.first.copy(balance = balance, rawBalance = rawBal,
                        phone = phone ?: match.first.phone)
                match != null && match.second >= 0.5 -> review += joined
                else -> addC += Customer(name = name, phone = phone, code = code,
                    balance = balance, rawBalance = rawBal)
            }
        }
        return ImportResult(addC, upC, addP, review, ignored)
    }

    fun fromCsv(text: String, existing: List<Customer>, kind: String?): ImportResult =
        analyze(Csv.parse(text), existing, kind)

    fun fromXlsx(file: File, existing: List<Customer>, kind: String?): ImportResult {
        val sheets = XlsxReader.read(file)
        if (sheets.isEmpty() || sheets.all { it.rows.isEmpty() })
            throw IllegalStateException("ملف Excel فارغ أو غير مقروء.")
        return analyze(sheets.flatMap { it.rows }, existing, kind)
    }

    fun fromPdf(file: File, existing: List<Customer>, shopName: String?, kind: String?): ImportResult {
        val rows = mutableListOf<List<String>>()
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            stripper.setSortTextPositions(true)
            val text = stripper.getText(doc)
            if (text.isBlank())
                throw IllegalStateException("الملف لا يحتوي نصاً قابلاً للاستخراج (ربما صفحات مصوّرة). الاستيراد يدعم PDF النصي حالياً.")
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.length < 2) return@forEach
                if (shopName != null && trimmed.contains(shopName)) return@forEach
                if (SKIP.containsMatchIn(trimmed)) return@forEach
                val cells = trimmed.split(Regex("\\s{2,}|\t")).map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.isEmpty()) return@forEach
                if (cells.size == 1) {
                    if (DataPreservation.parseAmount(cells[0]).value == null && cells[0].length > 2)
                        rows += listOf(cells[0], "")
                    return@forEach
                }
                rows += cells
            }
        }
        if (rows.isEmpty()) throw IllegalStateException("لم يُعثر على بيانات داخل ملف الـ PDF.")
        return analyze(rows, existing, kind ?: "CUSTOMER")
    }

    fun fromDb(file: File, existing: List<Customer>, kind: String?): ImportResult {
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
                val wanted = when (kind) {
                    "CUSTOMER" -> isCust
                    "PRODUCT" -> isProd
                    else -> isCust || isProd
                }
                if (!wanted) continue
                combined += cols
                db.rawQuery("SELECT * FROM $t LIMIT 5000", null).use { c ->
                    while (c.moveToNext()) {
                        combined += (0 until c.columnCount).map { i -> c.getString(i) ?: "" }
                    }
                }
            }
        }
        if (combined.isEmpty()) throw IllegalStateException("لم يُعثر على جداول عملاء/أصناف داخل قاعدة البيانات.")
        return analyze(combined, existing, kind)
    }

    suspend fun apply(ctx: android.content.Context, repo: MainRepo, type: String,
                      fileName: String, result: ImportResult) {
        val backup = repo.createBackup(true)
        val sid = repo.db.importDao().session(ImportSession(
            fileName = fileName, type = type, backupPath = backup?.filePath))
        var addC = 0; var upC = 0; var addP = 0
        result.addCustomers.forEach { c ->
            repo.db.customerDao().insert(c); addC++
            repo.db.importDao().record(ImportRecord(sessionId = sid, kind = "CUSTOMER",
                action = "ADD", sourceRaw = c.name, confidence = 1.0))
        }
        result.updateCustomers.forEach { c ->
            repo.db.customerDao().update(c.copy(updatedAt = System.currentTimeMillis())); upC++
            repo.db.importDao().record(ImportRecord(sessionId = sid, kind = "CUSTOMER",
                action = "UPDATE", sourceRaw = c.name, matchedId = c.id, confidence = 0.9))
        }
        result.addProducts.forEach { (p, q) ->
            repo.saveProduct(p, q, 0.0, null); addP++
            repo.db.importDao().record(ImportRecord(sessionId = sid, kind = "PRODUCT",
                action = "ADD", sourceRaw = p.nameRaw, confidence = 1.0))
        }
        result.review.forEach { r ->
            repo.db.importDao().record(ImportRecord(sessionId = sid, kind = "REVIEW",
                action = "REVIEW", sourceRaw = r, confidence = 0.0))
        }
        repo.db.importDao().updateSession(ImportSession(id = sid, fileName = fileName, type = type,
            finishedAt = System.currentTimeMillis(), addedCustomers = addC, updatedCustomers = upC,
            addedProducts = addP, updatedProducts = 0, ignored = result.ignored,
            reviewed = result.review.size, backupPath = backup?.filePath, status = "DONE"))
        repo.log("استيراد $type", "+$addC عميل، $upC تحديث، +$addP صنف، ${result.review.size} مراجعة")
    }
}
