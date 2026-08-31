package com.smartassistant.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.util.Fmt
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.SessionStatus
import com.smartassistant.app.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }
    var previewRows by remember { mutableStateOf<List<ImportRawRow>>(emptyList()) }
    var previewProducts by remember { mutableStateOf<List<Pair<Any, Double>>>(emptyList()) }
    var meta by remember { mutableStateOf<Triple<String, String, Long>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKindDialog by remember { mutableStateOf(false) }
    var pendingExt by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(ImportKind.CUSTOMER) }
    var sessionId by remember { mutableStateOf(-1L) }

    fun startImport(uri: Uri, kind: ImportKind) {
        scope.launch {
            error = null; previewRows = emptyList(); previewProducts = emptyList()
            progress = "جاري نسخ الملف..."
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = runCatching {
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                        }
                    }.getOrNull() ?: "import_file"
                    val f = File(ctx.cacheDir, System.currentTimeMillis().toString() + "_" + name)
                    ctx.contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    f to name
                }.getOrNull()
            }
            if (file == null) { progress = null; error = "تعذر قراءة الملف."; return@launch }
            val (f, name) = file
            val ext = name.substringAfterLast('.', "").lowercase()
            val type = ImportEngine.detectType(f, ext)
            val hash = ImportEngine.computeHash(f)

            // فحص تكرار Hash
            val existing = repo.db.importDao().findByHash(hash)
            if (existing != null) {
                error = "هذا الملف تم استيراده سابقاً (${existing.fileName}) في ${Fmt.ts(existing.startedAt)}"
                progress = null
                return@launch
            }

            // إنشاء جلسة استيراد
            val sid = repo.db.importDao().session(ImportSession(
                fileName = name,
                fileHash = hash,
                fileType = type,
                kind = kind.name,
                status = SessionStatus.PROCESSING.name
            ))
            sessionId = sid

            progress = "جاري تحليل الملف (${kindLabel(kind)})..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val shop = repo.db.shopDao().get()
                    when (type) {
                        "csv", "txt" -> ImportEngine.fromCsv(f.readText(), kind, sid)
                        "xlsx" -> ImportEngine.fromXlsx(f, kind, sid)
                        "pdf" -> ImportEngine.fromPdf(f, shop?.name, kind, sid)
                        "db", "sqlite", "sqlite3" -> ImportEngine.fromDb(f, kind, sid)
                        else -> null
                    }
                } catch (e: Throwable) {
                    error = e.message ?: "فشل تحليل الملف."
                    null
                }
            }
            progress = null
            if (result == null) {
                if (error == null) error = "نوع الملف غير مدعوم ($type)."
                repo.db.importDao().updateSession(ImportSession(
                    id = sid, fileName = name, fileHash = hash, fileType = type,
                    kind = kind.name, status = SessionStatus.FAILED.name
                ))
                return@launch
            }
            // حفظ الصفوف
            if (result.rows.isNotEmpty()) repo.db.importDao().insertRows(result.rows)
            previewRows = result.rows
            previewProducts = result.products
            meta = Triple(type.uppercase(), name, sid)

            repo.db.importDao().updateSession(ImportSession(
                id = sid, fileName = name, fileHash = hash, fileType = type,
                kind = kind.name,
                totalFound = result.rows.size + result.products.size,
                validCount = result.rows.size + result.products.size,
                totalCredit = result.totalCredit,
                totalDebit = result.totalDebit,
                net = result.totalDebit - result.totalCredit,
                status = SessionStatus.READY_TO_IMPORT.name
            ))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri, selectedKind)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الاستيراد الذكي", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("يفهم المحرك الجداول بأي ترتيب، والرصيد بالسالب/بين قوسين/بعمودي مدين-دائن/بكلمة له-عليه. يتم الاحتفاظ بالاسم الأصلي والعرضي والمطبّع (3 نسخ).",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
            item { ImportCard("قاعدة بيانات DB", "ملفات .db / .sqlite", Icons.Rounded.Storage) {
                pendingExt = "db"; showKindDialog = true } }
            item { ImportCard("Excel", "ملفات .xlsx", Icons.Rounded.TableChart) {
                pendingExt = "xlsx"; showKindDialog = true } }
            item { ImportCard("CSV", "ملفات .csv / .txt", Icons.Rounded.Description) {
                pendingExt = "csv"; showKindDialog = true } }
            item { ImportCard("PDF", "ملفات .pdf (نص حقيقي)", Icons.Rounded.PictureAsPdf) {
                pendingExt = "pdf"; showKindDialog = true } }

            item {
                progress?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.DeepBlue)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(color = AppColors.CyanAccent, strokeWidth = 2.dp)
                            Text(it, color = Color.White)
                        }
                    }
                }
                error?.let {
                    Card(colors = CardDefaults.cardColors(containerColor = AppColors.RedDanger.copy(alpha = 0.1f))) {
                        Column(Modifier.padding(16.dp)) {
                            Text("حدث خطأ أثناء تنفيذ العملية", color = AppColors.RedDanger)
                            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                            TextButton(onClick = { error = null }) { Text("إعادة المحاولة") }
                        }
                    }
                }
            }

            item {
                if (previewRows.isNotEmpty() || previewProducts.isNotEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("معاينة الاستيراد (${kindLabel(selectedKind)})", style = MaterialTheme.typography.titleLarge)
                            val totalCredit = previewRows.sumOf { it.credit }
                            val totalDebit = previewRows.sumOf { it.debit }
                            val net = totalDebit - totalCredit
                            if (selectedKind == ImportKind.CUSTOMER) {
                                Text("سيتم إضافة/تحديث: ${previewRows.size} عميل", color = AppColors.GreenSuccess)
                                Text("إجمالي لهم: ${Fmt.money(totalCredit)} | عليهم: ${Fmt.money(totalDebit)} | الصافي: ${Fmt.money(net)}",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("سيتم إضافة: ${previewProducts.size} صنف", color = AppColors.GreenSuccess)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        progress = "جاري الحفظ في قاعدة البيانات (Transaction)..."
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                repo.createBackup(true)
                                                if (selectedKind == ImportKind.CUSTOMER) {
                                                    ImportEngine.apply(repo, sessionId, previewRows)
                                                } else {
                                                    repo.db.runInTransaction {
                                                        previewProducts.forEach { (p, q) ->
                                                            val prod = p as com.smartassistant.app.data.local.entity.Product
                                                            repo.saveProduct(prod, q, 0.0, null)
                                                        }
                                                    }
                                                    val s = repo.db.importDao().sessionById(sessionId)
                                                    if (s != null) repo.db.importDao().updateSession(s.copy(
                                                        status = SessionStatus.COMPLETED.name,
                                                        finishedAt = System.currentTimeMillis(),
                                                        validCount = previewProducts.size
                                                    ))
                                                }
                                            }.onFailure { e -> error = e.message ?: "فشل الحفظ" }
                                        }
                                        progress = null
                                        previewRows = emptyList(); previewProducts = emptyList()
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                                    Text("تأكيد الاستيراد", color = Color.White)
                                }
                                OutlinedButton(onClick = { previewRows = emptyList(); previewProducts = emptyList() }) {
                                    Text("إلغاء")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("أول 5 سجلات:", style = MaterialTheme.typography.labelMedium, color = AppColors.Gray)
                        }
                    }
                }
            }

            items(previewRows.take(5)) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(row.nameDisplay, style = MaterialTheme.typography.bodyLarge)
                        Text("لك: ${Fmt.money(row.credit)} | عليك: ${Fmt.money(row.debit)} | الصافي: ${Fmt.money(row.net)}",
                            style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                    }
                }
            }
        }
    }

    if (showKindDialog) {
        AlertDialog(
            onDismissRequest = { showKindDialog = false },
            title = { Text("نوع البيانات في الملف") },
            text = { Text("ملف ${pendingExt.uppercase()}: ماذا تريد أن تستورد منه؟") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        selectedKind = ImportKind.CUSTOMER; showKindDialog = false
                        picker.launch(arrayOf("*/*"))
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                        Text("العملاء والأرصدة", color = Color.White)
                    }
                    Button(onClick = {
                        selectedKind = ImportKind.PRODUCT; showKindDialog = false
                        picker.launch(arrayOf("*/*"))
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.CyanAccent)) {
                        Text("الأصناف والمخزون", color = Color.White)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showKindDialog = false }) { Text("إلغاء") } })
    }
}

private fun kindLabel(kind: ImportKind): String = when (kind) {
    ImportKind.CUSTOMER -> "عملاء"
    ImportKind.PRODUCT -> "أصناف"
}

@Composable
fun ImportCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(48.dp), shape = MaterialTheme.shapes.medium,
                color = AppColors.CyanAccent.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null,
                        tint = AppColors.CyanAccent, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
            Icon(imageVector = Icons.Rounded.FileOpen, contentDescription = "اختيار ملف", tint = AppColors.PrimaryBlue)
        }
    }
}
