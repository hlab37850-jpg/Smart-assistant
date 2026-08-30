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
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Csv
import com.smartassistant.app.util.Export
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class ReportRow(val title: String, val subtitle: String, val value: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(nav: NavController) {
    var selected by remember { mutableStateOf<String?>(null) }
    if (selected == null) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("التقارير", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
        }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ReportCard("تقرير العملاء", "كل العملاء وأرصدتهم", Icons.Rounded.People) { selected = "CUSTOMERS" } }
                item { ReportCard("تقرير الأرصدة", "لهم / عليهم / الصافي", Icons.Rounded.AccountBalance) { selected = "BALANCES" } }
                item { ReportCard("تقرير الاستحقاقات", "كل المواعيد وحالاتها", Icons.Rounded.Schedule) { selected = "DUES" } }
                item { ReportCard("تقرير المنسيين", "تجاوزوا الموعد بدون متابعة", Icons.Rounded.PersonOff) { selected = "FORGOTTEN" } }
                item { ReportCard("تقرير الأصناف", "كل الأصناف والكميات", Icons.Rounded.Inventory) { selected = "PRODUCTS" } }
                item { ReportCard("تقرير النواقص", "منخفض وناقص", Icons.Rounded.Warning) { selected = "LOW" } }
                item { ReportCard("تقرير النشاط", "سجل العمليات", Icons.Rounded.History) { selected = "ACTIVITY" } }
                item { ReportCard("تقرير الاستيراد", "جلسات الاستيراد", Icons.Rounded.FileOpen) { selected = "IMPORTS" } }
            }
        }
    } else {
        ReportView(selected!!, onBack = { selected = null })
    }
}

@Composable
fun ReportCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.size(44.dp), shape = MaterialTheme.shapes.medium,
                color = AppColors.PrimaryBlue.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = AppColors.PrimaryBlue, Modifier.size(24.dp))
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportView(type: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ReportRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(type) {
        loading = true
        rows = buildReport(repo, type)
        loading = false
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("معاينة التقرير", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = {
                    scope.launch {
                        val data = mutableListOf(listOf("العنوان", "التفاصيل", "القيمة"))
                        rows.forEach { data += listOf(it.title, it.subtitle, it.value) }
                        val ok = Export.csv(ctx, "report_${type}_${System.currentTimeMillis()}.csv", Csv.encode(data))
                        msg = if (ok) "تم التصدير إلى مجلد التنزيلات" else "فشل التصدير"
                    }
                }) { Icon(Icons.Rounded.FileDownload, "تصدير", tint = Color.White) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            msg?.let { Text(it, Modifier.padding(16.dp), color = AppColors.GreenSuccess) }
            if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد بيانات لهذا التقرير", color = AppColors.Gray)
            }
            else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows.size) { i ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(rows[i].title, style = MaterialTheme.typography.bodyLarge)
                                Text(rows[i].subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                            }
                            Text(rows[i].value, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun buildReport(repo: MainRepo, type: String): List<ReportRow> = runCatching {
    when (type) {
        "CUSTOMERS" -> repo.allCustomers().map { ReportRow(it.name, it.phone ?: "—", Fmt.money(it.balance)) }
        "BALANCES" -> {
            val cs = repo.allCustomers()
            val they = cs.filter { it.balance > 0 }.sumOf { it.balance }
            val we = cs.filter { it.balance < 0 }.sumOf { -it.balance }
            listOf(ReportRow("إجمالي لهم", "${cs.count { it.balance < 0 }} عميل", Fmt.money(we)),
                ReportRow("إجمالي عليهم", "${cs.count { it.balance > 0 }} عميل", Fmt.money(they)),
                ReportRow("الصافي", "", Fmt.money(they - we)))
        }
        "DUES" -> (repo.db.dueDao().all().firstOrNull() ?: emptyList()).map {
            ReportRow(it.customerName, "${it.due.date} ${it.due.time}", Fmt.money(it.balance))
        }
        "FORGOTTEN" -> (repo.forgotten.firstOrNull() ?: emptyList()).map {
            ReportRow(it.customerName, "متأخر ${Fmt.daysSince(it.due.date)} يوم", Fmt.money(it.balance))
        }
        "PRODUCTS" -> repo.db.productDao().allSync().map { p ->
            val inv = repo.inventoryOnce(p.id)
            ReportRow(p.nameRaw, p.code ?: "—", Fmt.money(inv?.qty ?: 0.0))
        }
        "LOW" -> (repo.lowStock.firstOrNull() ?: emptyList()).map {
            ReportRow(it.product.nameRaw, "الحد: ${Fmt.money(it.minQty ?: 0.0)}", Fmt.money(it.qty ?: 0.0))
        }
        "ACTIVITY" -> (repo.allActivity.firstOrNull() ?: emptyList()).map {
            ReportRow(it.action, it.details, Fmt.ts(it.createdAt))
        }
        "IMPORTS" -> (repo.importSessions.firstOrNull() ?: emptyList()).map {
            ReportRow(it.fileName, "${it.type} — +${it.addedCustomers} عميل / +${it.addedProducts} صنف", it.status)
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())
