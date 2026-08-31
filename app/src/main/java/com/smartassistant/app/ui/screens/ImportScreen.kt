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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.extractors.PdfSmartImporter
import com.smartassistant.app.importer.extractors.deleteSessionData
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.SessionStatus
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt
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
    var error by remember { mutableStateOf<String?>(null) }
    var showKindDialog by remember { mutableStateOf(false) }
    var pendingExt by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(ImportKind.CUSTOMER) }
    var sessionId by remember { mutableStateOf(-1L) }
    var dupSession by remember { mutableStateOf<ImportSession?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun startImport(uri: Uri, kind: ImportKind, force: Boolean) {
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

            val existing = repo.db.importDao().findByHash(hash)
            if (existing != null && !force) {
                progress = null; dupSession = existing; pendingUri = uri
                return@launch
            }
            if (existing != null && force) {
                withContext(Dispatchers.IO) { deleteSessionData(repo, existing.id) }
            }

            val sid = repo.db.importDao().session(ImportSession(
                fileName = name, fileHash = hash, fileType = type,
                kind = kind.name, status = SessionStatus.PROCESSING.name
            ))
            sessionId = sid

            progress = "جاري التحليل بالإحداثيات (${kindLabel(kind)})..."
            val result = withContext(Dispatchers.IO) {
                try {
                    val shop = repo.db.shopDao().get()
                    when (type) {
                        "csv", "txt" -> ImportEngine.fromCsv(f.readText(), kind, sid)
                        "xlsx" -> ImportEngine.fromXlsx(f, kind, sid)
                        "pdf" -> PdfSmartImporter.parse(f, shop?.name, kind, sid)
                        "db", "sqlite", "sqlite3" -> ImportEngine.fromDb(f, kind, sid)
                        else -> null
                    }
                } catch (e: Throwable) { error = e.message ?: "فشل تحليل الملف."; null }
            }
            progress = null
            if (result == null) {
                if (error == null) error = "نوع الملف غير مدعوم ($type)."
                return@launch
            }
            if (result.rows.isNotEmpty()) repo.db.importDao().insertRows(result.rows)
            previewRows = result.rows
            previewProducts = result.products
            repo.db.importDao().updateSession(ImportSession(
                id = sid, fileName = name, fileHash = hash, fileType = type, kind = kind.name,
                totalFound = result.rows.size + result.products.size,
                validCount = result.rows.size + result.products.size,
                reviewCount = result.rows.count { it.status == "WARNING" },
                ignoredCount = result.ignored,
                totalCredit = result.totalCredit, totalDebit = result.totalDebit,
                net = result.totalDebit - result.totalCredit,
                status = SessionStatus.READY_TO_IMPORT.name
            ))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri, selectedKind, force = false)
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
                Text("استخراج بالإحداثيات: كل خلية تُربط بعمودها عبر موقعها. يفهم له/عليه/مدين/دائن/بالسالب/بين قوسين. يحتفظ بالاسم الخام والعرضي والمطبّع.",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
            item { ImportCard("قاعدة بيانات DB", "ملفات .db / .sqlite", Icons.Rounded.Storage) { pendingExt = "db"; showKindDialog = true } }
            item { ImportCard("Excel", "ملفات .xlsx", Icons.Rounded.TableChart) { pendingExt = "xlsx"; showKindDialog = true } }
            item { ImportCard("CSV", "ملفات .csv / .txt", Icons.Rounded.Description) { pendingExt = "csv"; showKindDialog = true } }
            item { ImportCard("PDF", "ملفات .pdf", Icons.Rounded.PictureAsPdf) { pendingExt = "pdf"; showKindDialog = true } }

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
                    val totalCredit = previewRows.sumOf { it.credit }
                    val totalDebit = previewRows.sumOf { it.debit }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("معاينة الاستيراد (${kindLabel(selectedKind)})", style = MaterialTheme.typography.titleLarge)
                            if (selectedKind == ImportKind.CUSTOMER) {
                                Text("سجلات: ${previewRows.size} | تحذيرات: ${previewRows.count { it.status == "WARNING" }}", color = AppColors.GreenSuccess)
                                Text("لك: ${Fmt.money(totalCredit)} | عليك: ${Fmt.money(totalDebit)} | الصافي: ${Fmt.money(totalDebit - totalCredit)}",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("أصناف: ${previewProducts.size}", color = AppColors.GreenSuccess)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        progress = "جاري الحفظ (Transaction + نسخة احتياطية)..."
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                repo.createBackup(true)
                                                if (selectedKind == ImportKind.CUSTOMER) {
                                                    ImportEngine.apply(repo, sessionId, previewRows)
                                                } else {
                                                    previewProducts.forEach { (p, q) ->
                                                        repo.saveProduct(p as com.smartassistant.app.data.local.entity.Product, q, 0.0, null)
                                                    }
                                                    val s = repo.db.importDao().sessionById(sessionId)
                                                    if (s != null) repo.db.importDao().updateSession(s.copy(
                                                        status = SessionStatus.COMPLETED.name,
                                                        finishedAt = System.currentTimeMillis(),
                                                        validCount = previewProducts.size))
                                                }
                                            }.onFailure { e -> error = e.message ?: "فشل الحفظ" }
                                        }
                                        progress = null; previewRows = emptyList(); previewProducts = emptyList()
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                                    Text("تأكيد الاستيراد", color = Color.White)
                                }
                                OutlinedButton(onClick = { previewRows = emptyList(); previewProducts = emptyList() }) { Text("إلغاء") }
                            }
                        }
                    }
                }
            }

            items(previewRows.take(6)) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.nameDisplay, style = MaterialTheme.typography.bodyLarge)
                            Text("ثقة ${row.confidenceScore}%", style = MaterialTheme.typography.labelSmall,
                                color = if (row.confidenceScore >= 90) AppColors.GreenSuccess else AppColors.GoldAccent)
                        }
                        Text("لك: ${Fmt.money(row.credit)} | عليك: ${Fmt.money(row.debit)} | الصافي: ${Fmt.money(row.net)} ${row.currency ?: ""}",
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
            text = { Text("ملف ${pendingExt.uppercase()}: ماذا تريد أن تستورد؟") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { selectedKind = ImportKind.CUSTOMER; showKindDialog = false; picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                        Text("العملاء والأرصدة", color = Color.White)
                    }
                    Button(onClick = { selectedKind = ImportKind.PRODUCT; showKindDialog = false; picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.CyanAccent)) {
                        Text("الأصناف والمخزون", color = Color.White)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showKindDialog = false }) { Text("إلغاء") } })
    }

    dupSession?.let { dup ->
        AlertDialog(
            onDismissRequest = { dupSession = null },
            title = { Text("ملف مستورد سابقاً") },
            text = { Text("هذا الملف (${dup.fileName}) تم استيراده من قبل. هل تريد حذف بيانات الجلسة السابقة وإعادة الاستيراد بالمحرك المصحح؟") },
            confirmButton = {
                Button(onClick = {
                    val u = pendingUri
                    dupSession = null
                    if (u != null) startImport(u, selectedKind, force = true)
                }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDanger)) {
                    Text("حذف السابق وإعادة الاستيراد", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { dupSession = null }) { Text("إلغاء") } })
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
