package com.smartassistant.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.dao.DueWithCustomer
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.DueDate
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.notifications.ReminderBuilder
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.DataPreservation
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuesScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("TODAY") }
    var showAdd by remember { mutableStateOf(false) }

    val dues by repo.dues(tab).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("الاستحقاقات", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, containerColor = AppColors.PrimaryBlue) {
                Icon(Icons.Rounded.Add, "موعد جديد", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("TODAY" to "اليوم", "TOMORROW" to "غداً", "WEEK" to "هذا الأسبوع",
                    "OVERDUE" to "المتأخرة", "ALL" to "الكل").forEach { (key, label) ->
                    FilterChip(selected = tab == key, onClick = { tab = key }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryBlue, selectedLabelColor = Color.White))
                }
            }
            if (dues.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد استحقاقات في هذا التبويب", color = AppColors.Gray)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(dues.size) { i ->
                        val d = dues[i]
                        DueCard(d,
                            onFollow = { scope.launch { repo.markFollowed(d.due.id) } },
                            onRemind = { scope.launch { remind(ctx, repo, d) } })
                    }
                }
            }
        }
    }

    if (showAdd) AddDueDialog(repo, onDismiss = { showAdd = false })
}

@Composable
fun DueCard(d: DueWithCustomer, onFollow: () -> Unit, onRemind: () -> Unit) {
    val today = Fmt.today()
    val badge = when {
        d.due.date < today -> "متأخر ${Fmt.daysSince(d.due.date)} يوم" to AppColors.RedDanger
        d.due.date == today -> "اليوم" to AppColors.GoldAccent
        d.due.date == Fmt.plusDays(1) -> "غداً" to AppColors.CyanAccent
        else -> d.due.date to AppColors.Gray
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.customerName, style = MaterialTheme.typography.titleMedium)
                    Text("${d.due.date} ${d.due.time}", style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                }
                Text(Fmt.money(d.balance), style = MaterialTheme.typography.titleMedium,
                    color = if (d.balance > 0) AppColors.RedDanger else AppColors.GreenSuccess)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(color = badge.second.copy(alpha = 0.1f), shape = MaterialTheme.shapes.small) {
                    Text(badge.first, color = badge.second,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
                Row {
                    IconButton(onClick = onRemind) { Icon(Icons.Rounded.Send, "تذكير", tint = AppColors.GreenSuccess) }
                    if (d.due.followedUp == 0) {
                        IconButton(onClick = onFollow) { Icon(Icons.Rounded.CheckCircle, "تمت المتابعة", tint = AppColors.GoldAccent) }
                    } else {
                        Text("تمت المتابعة ✓", style = MaterialTheme.typography.labelSmall, color = AppColors.GreenSuccess)
                    }
                }
            }
        }
    }
}

private suspend fun remind(ctx: android.content.Context, repo: MainRepo, d: DueWithCustomer) {
    val shop = repo.db.shopDao().get()
    val cust = repo.customer(d.due.customerId)
    val msg = ReminderBuilder.build(shop?.name ?: "المحل", d.customerName, d.balance,
        d.due.date, d.due.time, shop?.phone ?: "", shop?.address ?: "")
    val phone = DataPreservation.parsePhone(cust?.phone)
    if (phone != null) {
        val url = "https://wa.me/$phone?text=" + URLEncoder.encode(msg, "UTF-8")
        val ok = kotlin.runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.isSuccess
        if (!ok) shareText(ctx, msg)
    } else shareText(ctx, msg)
}

private fun shareText(ctx: android.content.Context, msg: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg) }
    kotlin.runCatching { ctx.startActivity(Intent.createChooser(i, "إرسال تذكير")) }
}

@Composable
fun AddDueDialog(repo: MainRepo, onDismiss: () -> Unit) {
    var customers by remember { mutableStateOf<List<Customer>>(emptyList()) }
    var selectedId by remember { mutableStateOf(-1L) }
    var date by remember { mutableStateOf(Fmt.today()) }
    var time by remember { mutableStateOf("10:00") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { customers = repo.allCustomers() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إنشاء موعد استحقاق") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = customers.find { it.id == selectedId }?.name ?: "اختر العميل",
                        onValueChange = {}, readOnly = true, label = { Text("العميل") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        customers.take(50).forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) },
                                onClick = { selectedId = c.id; expanded = false })
                        }
                    }
                }
                OutlinedTextField(date, { date = it }, label = { Text("التاريخ (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("الوقت (HH:MM)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(enabled = selectedId > 0, onClick = {
                scope.launch {
                    repo.saveDue(DueDate(customerId = selectedId, date = date.trim(), time = time.trim()))
                    onDismiss()
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                Text("حفظ الموعد", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
