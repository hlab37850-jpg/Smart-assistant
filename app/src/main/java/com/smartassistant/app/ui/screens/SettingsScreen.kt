package com.smartassistant.app.ui.screens

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
import com.smartassistant.app.data.local.entity.Category
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    val theme by repo.prefs.themeMode.collectAsState(initial = "SYSTEM")
    var msg by remember { mutableStateOf<String?>(null) }
    var showRestoreConfirm by remember { mutableStateOf<String?>(null) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    val backups by repo.backups.collectAsState(initial = emptyList())
    val categories by repo.categories.collectAsState(initial = emptyList())
    val activity by repo.allActivity.collectAsState(initial = emptyList())

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الإعدادات", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { msg?.let { Text(it, color = AppColors.GreenSuccess) } }
            item {
                SettingItem("بيانات المحل", "الاسم، الشعار، الهاتف، العنوان", Icons.Rounded.Store) {
                    nav.navigate(Routes.SHOP_SETUP)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("المظهر", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("SYSTEM" to "تلقائي", "LIGHT" to "فاتح", "DARK" to "داكن").forEach { (k, label) ->
                                FilterChip(selected = theme == k, onClick = {
                                    scope.launch { repo.prefs.setThemeMode(k) }
                                }, label = { Text(label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppColors.PrimaryBlue, selectedLabelColor = Color.White))
                            }
                        }
                    }
                }
            }
            item {
                SettingItem("الاستيراد الذكي", "DB / Excel / CSV / PDF", Icons.Rounded.FileOpen) {
                    nav.navigate(Routes.IMPORT)
                }
            }
            item {
                SettingItem("المساعد الذكي", "اسأل بلغة طبيعية", Icons.Rounded.SmartToy) {
                    nav.navigate(Routes.AI)
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("النسخ الاحتياطي", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = {
                            scope.launch {
                                val b = repo.createBackup(false)
                                msg = if (b != null) "تم إنشاء نسخة (${b.size / 1024} KB)" else "فشل النسخ"
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.GreenSuccess)) {
                            Icon(imageVector = Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("إنشاء نسخة الآن", color = Color.White)
                        }
                        backups.take(5).forEach { b ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${Fmt.ts(b.createdAt)} — ${b.size / 1024} KB",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text(if (b.auto == 1) "تلقائية (قبل استيراد)" else "يدوية",
                                        style = MaterialTheme.typography.labelSmall, color = AppColors.Gray)
                                }
                                TextButton(onClick = { showRestoreConfirm = b.filePath }) { Text("استعادة") }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("التصنيفات", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { showCategoryDialog = true }) { Text("إضافة تصنيف") }
                        }
                        categories.forEach { c ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = { scope.launch { repo.archiveCategory(c.id) } }) {
                                    Text("أرشفة", color = AppColors.RedDanger)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("سجل العمليات", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        activity.take(15).forEach { l ->
                            Text("• ${l.action}: ${l.details} (${Fmt.ts(l.createdAt)})",
                                style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                        }
                    }
                }
            }
            item {
                Text("المساعد الذكي v1.1 — يعمل بدون إنترنت بالكامل",
                    style = MaterialTheme.typography.labelSmall, color = AppColors.Gray)
            }
        }
    }

    showRestoreConfirm?.let { path ->
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = null },
            title = { Text("استعادة النسخة؟") },
            text = { Text("سيتم استبدال البيانات الحالية بالنسخة الاحتياطية. هذا الإجراء خطير.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val ok = repo.restoreBackup(path)
                        msg = if (ok) "تمت الاستعادة — أعد تشغيل التطبيق" else "فشلت الاستعادة"
                        showRestoreConfirm = null
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.RedDanger)) {
                    Text("استعادة", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = null }) { Text("إلغاء") } })
    }

    if (showCategoryDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("إضافة تصنيف") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("اسم التصنيف") }) },
            confirmButton = {
                Button(onClick = {
                    scope.launch { repo.saveCategory(Category(name = name.trim())); showCategoryDialog = false }
                }, enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                    Text("حفظ", color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { showCategoryDialog = false }) { Text("إلغاء") } })
    }
}

@Composable
fun SettingItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(44.dp), shape = MaterialTheme.shapes.medium,
                color = AppColors.PrimaryBlue.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(imageVector = icon, contentDescription = null,
                        tint = AppColors.PrimaryBlue, modifier = Modifier.size(24.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
        }
    }
}
