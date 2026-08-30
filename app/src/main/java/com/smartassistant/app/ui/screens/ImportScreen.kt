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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.ImportEngine
import com.smartassistant.app.importer.ImportResult
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
    var preview by remember { mutableStateOf<ImportResult?>(null) }
    var meta by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun startImport(uri: Uri) {
        scope.launch {
            error = null; preview = null; progress = "جاري نسخ الملف..."
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
            if (file == null) { progress = null; error = "تعذر قراءة الملف"; return@launch }
            val (f, name) = file
            val ext = name.substringAfterLast('.', "").lowercase()
            progress = "جاري تحليل الملف..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val existing = repo.allCustomers()
                    val shop = repo.db.shopDao().get()
                    when (ext) {
                        "csv", "txt" -> ImportEngine.fromCsv(f.readText(), existing)
                        "xlsx" -> ImportEngine.fromXlsx(f, existing)
                        "pdf" -> ImportEngine.fromPdf(f, existing, shop?.name)
                        "db", "sqlite", "sqlite3" -> ImportEngine.fromDb(f, existing)
                        else -> null
                    }
                }.getOrNull()
            }
            progress = null
            if (result == null) { error = "نوع ملف غير مدعوم أو ملف تالف"; return@launch }
            meta = ext.uppercase() to name
            preview = result
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الاستيراد الذكي", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("يتم استخراج: العملاء + الأرصدة الحالية + الأصناف + المخزون فقط. يُهمل كل ما عدا ذلك تلقائياً.",
                style = MaterialTheme.typography.bodySmall, color = AppColors.Gray) }
            item { ImportCard("قاعدة بيانات DB", "ملفات .db / .sqlite", Icons.Rounded.Storage) { picker.launch(arrayOf("*/*")) } }
            item { ImportCard("Excel", "ملفات .xlsx", Icons.Rounded.TableChart) { picker.launch(arrayOf("*/*")) } }
            item { ImportCard("CSV", "ملفات .csv / .txt", Icons.Rounded.Description) { picker.launch(arrayOf("*/*")) } }
            item { ImportCard("PDF", "ملفات .pdf (نص حقيقي)", Icons.Rounded.PictureAsPdf) { picker.launch(arrayOf("*/*")) } }

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
                preview?.let { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("معاينة الاستيراد", style = MaterialTheme.typography.titleLarge)
                            Text("سيتم إضافة: ${p.addCustomers.size} عميل، ${p.addProducts.size} صنف",
                                color = AppColors.GreenSuccess)
                            Text("سيتم تحديثه: ${p.updateCustomers.size} عميل", color = AppColors.GoldAccent)
                            Text("يحتاج إلى مراجعة: ${p.review.size} — سيتم تجاهله: ${p.ignored}",
                                color = AppColors.Gray)
                            p.review.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = AppColors.RedDanger) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch {
                                        progress = "جاري التطبيق مع نسخة احتياطية تلقائية..."
                                        withContext(Dispatchers.IO) {
                                            ImportEngine.apply(ctx, repo, meta?.first ?: "FILE", meta?.second ?: "file", p)
                                        }
                                        progress = null; preview = null
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                                    Text("تأكيد الاستيراد", color = Color.White)
                                }
                                OutlinedButton(onClick = { preview = null }) { Text("إلغاء") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImportCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(48.dp), shape = MaterialTheme.shapes.medium,
                color = AppColors.CyanAccent.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = AppColors.CyanAccent, Modifier.size(26.dp)) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
            Icon(Icons.Rounded.FileOpen, "اختيار ملف", tint = AppColors.PrimaryBlue)
        }
    }
}
