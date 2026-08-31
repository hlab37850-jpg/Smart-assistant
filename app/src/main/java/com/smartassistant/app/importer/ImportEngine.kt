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

    /** kind: "CUSTOMER" = عملاء فقط، "PRODUCT" = أصناف فقط، null = تلقائي */
    fun analyze(rows: List<List<String>>, existing: List<Customer>, kind: String?): ImportResult {
        if (rows.isEmpty()) return ImportResult()
        var header = -1
        var nameCol = 0; var phoneCol = -1; var balCol = -1; var qtyCol = -1; var codeCol = -1
        for ((i, r) in rows.withIndex()) {
            val joined = r.joinToString(" ")
            if (joined.contains(Regex("اسم|عميل|صنف|هاتف|رصيد|كميه|كمية"))) {
                header = i
                nameCol = r.indexOfFirst { it.contains(Regex("اسم|عميل|صنف")) }.coerceAtLeast(0)
                phoneCol = r.indexOfFirst { it.contains(Regex("هاتف|جوال|phone|mobile")) }
                balCol = r.indexOfFirst { it.contains(Regex("رصيد|balance|مدين|دائن")) }
                qtyCol = r.indexOfFirst { it.contains(Regex("كميه|كمية|qty")) }
                codeCol = r.indexOfFirst { it.contains(Regex("كود|code")) }
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
            val phone = if (phoneCol >= 0) DataPreservation.parsePhone(r.getOrElse(phoneCol) { null }) else null
            val balRaw = if (balCol >= 0) r.getOrElse(balCol) { "" } else null
            val qtyRaw = if (qtyCol >= 0) r.getOrElse(qtyCol) { "" } else null
            val code = if (codeCol >= 0) DataPreservation.keepRaw(r.getOrElse(codeCol) { "" }).ifEmpty { null } else null
            if (name.isEmpty()) { review += joined; continue }
            val isProduct = if (kind != null) kind == "PRODUCT"
                            else (qtyRaw != null && balRaw == null)
            if (isProduct) {
                val q = DataPreservation.parseAmount(qtyRaw).value
                    ?: DataPreservation.parseAmount(balRaw).value ?: 0.0
                addP += Product(nameRaw = name, code = code) to q
                continue
            }
            val parsed = DataPreservation.parseAmount(balRaw)
            if (balRaw != null && parsed.value == null) { review += joined; continue }
            val balance = parsed.value ?: 0.0
            if (kotlin.math.abs(balance) > 1_000_000_000) { review += joined; continue }
            val match = existing
                .map { it to Matching.confidence(it, null, phone, code, name) }
                .maxByOrNull { it.second }
            when {
                match != null && match.second >= 0.8 ->
                    upC += match.first.copy(balance = balance, rawBalance = parsed.raw.ifEmpty { balRaw },
                        phone = phone ?: match.first.phone)
                match != null && match.second >= 0.5 -> review += joined
                else -> addC += Customer(name = name, phone = phone, code = code,
                    balance = balance, rawBalance = parsed.raw.ifEmpty { balRaw })
            }
        }
        return ImportResult(addC, upC, addP, review, ignored)
    }

    fun fromCsv(text: String, existing: List<Customer>, kind: String?): ImportResult =
        analyze(Csv.parse(text), existing, kind)

    fun fromXlsx(file: File, existing: List<Customer>, kind: String?): ImportResult {
        val sheets = XlsxReader.read(file)
        return analyze(sheets.flatMap { it.rows }, existing, kind)
    }

    fun fromPdf(file: File, existing: List<Customer>, shopName: String?, kind: String?): ImportResult {
        val rows = mutableListOf<List<String>>()
        rows += if (kind == "PRODUCT") listOf("اسم الصنف", "كمية") else listOf("اسم العميل", "رصيد")
        PDDocument.load(file).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            text.lines().forEach { line ->
                val tokens = line.trim().split(Regex("\\s+"))
                if (tokens.size >= 2) {
                    val joined = line.trim()
                    if (shopName != null && joined.contains(shopName)) return@forEach
                    if (SKIP.containsMatchIn(joined)) return@forEach
                    val lastNum = tokens.lastOrNull { DataPreservation.parseAmount(it).value != null }
                    val name = tokens.dropLast(if (lastNum != null) 1 else 0).joinToString(" ").trim()
                    if (name.isBlank()) return@forEach
                    rows += listOf(name, lastNum ?: "")
                }
            }
        }
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
