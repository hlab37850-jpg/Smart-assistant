package com.smartassistant.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.extractors.PdfSmartImporter
import com.smartassistant.app.importer.extractors.deleteSessionData
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
    var error by remember { mutableStateOf<String?>(null) }
    var showKindDialog by remember { mutableStateOf(false) }
    var pendingExt by remember { mutableStateOf("") }
    var selectedKind by remember { mutableStateOf(ImportKind.CUSTOMER) }
    var dupSession by remember { mutableStateOf<ImportSession?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun startImport(uri: Uri, kind: ImportKind, force: Boolean) {
        scope.launch {
            error = null
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
            val type = com.smartassistant.app.importer.ImportEngine.detectType(f, ext)
            val hash = com.smartassistant.app.importer.ImportEngine.computeHash(f)
            val existing = repo.db.importDao().findByHash(hash)
            if (existing != null && !force) {
                progress = null; dupSession = existing; pendingUri = uri; return@launch
            }
            if (existing != null && force) withContext(Dispatchers.IO) { deleteSessionData(repo, existing.id) }

            val sid = repo.db.importDao().session(ImportSession(
                fileName = name, fileHash = hash, fileType = type,
                kind = kind.name, status = SessionStatus.PROCESSING.name))

            progress = "جاري التحليل (${if (kind == ImportKind.CUSTOMER) "عملاء" else "أصناف"})..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (type) {
                        "csv", "txt" -> com.smartassistant.app.importer.ImportEngine.fromCsv(f.readText(), kind, sid)
                        "xlsx" -> com.smartassistant.app.importer.ImportEngine.fromXlsx(f, kind, sid)
                        "pdf" -> PdfSmartImporter.parse(f, null, kind, sid)
                        "db", "sqlite", "sqlite3" -> com.smartassistant.app.importer.ImportEngine.fromDb(f, kind, sid)
                        else -> null
                    }
                }.getOrNull()
            }
            progress = null
            if (result == null) { error = "نوع الملف غير مدعوم أو فشل التحليل ($type)."; return@launch }
            if (result.rows.isNotEmpty()) repo.db.importDao().insertRows(result.rows)
            repo.db.importDao().updateSession(ImportSession(
                id = sid, fileName = name, fileHash = hash, fileType = type, kind = kind.name,
                totalFound = result.rows.size + result.products.size,
                validCount = result.rows.size + result.products.size,
                reviewCount = 0, ignoredCount = result.ignored,
                totalCredit = result.totalCredit, totalDebit = result.totalDebit,
                net = result.totalDebit - result.totalCredit,
                status = SessionStatus.READY_TO_IMPORT.name))
            nav.navigate("review/$sid")
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
                Text("يدعم المحرك: الأسطر الكاملة، والخلايا المقسمة على أسطر منفصلة. يستخرج العملاء/الأصناف + الأرصدة/الكميات فقط.",
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
            text = { Text("هذا الملف (${dup.fileName}) تم استيراده من قبل. هل تريد حذف بيانات الجلسة السابقة وإعادة الاستيراد؟") },
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
