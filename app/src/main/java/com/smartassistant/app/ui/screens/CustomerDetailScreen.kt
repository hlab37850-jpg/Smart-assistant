package com.smartassistant.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.DueDate
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.notifications.ReminderBuilder
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.DataPreservation
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerDetailScreen(nav: NavController, customerId: Long) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var customer by remember { mutableStateOf<Customer?>(null) }
    var notes by remember { mutableStateOf<List<com.smartassistant.app.data.local.entity.CustomerNote>>(emptyList()) }
    var newNote by remember { mutableStateOf("") }
    var showSchedule by remember { mutableStateOf(false) }

    LaunchedEffect(customerId) {
        customer = repo.customer(customerId)
        repo.notes(customerId).collect { notes = it }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(customer?.name ?: "تفاصيل العميل", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.editCustomer(customerId)) }) {
                    Icon(Icons.Rounded.Edit, "تعديل", tint = Color.White)
                }
                IconButton(onClick = {
                    scope.launch { repo.archiveCustomer(customerId); nav.popBackStack() }
                }) { Icon(Icons.Rounded.Archive, "أرشفة", tint = Color.White) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        val c = customer
        if (c == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoRow("الاسم", c.name)
                            InfoRow("الهاتف", c.phone ?: "—")
                            InfoRow("الكود", c.code ?: "—")
                            InfoRow("العنوان", c.address ?: "—")
                            InfoRow("الرصيد", Fmt.money(c.balance),
                                color = if (c.balance > 0) AppColors.RedDanger else AppColors.GreenSuccess)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showSchedule = true }, Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تحديد موعد", color = Color.White)
                        }
                        Button(onClick = { sendReminder(ctx, repo, c) }, Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.GreenSuccess)) {
                            Icon(Icons.Rounded.Send, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("إرسال تذكير", color = Color.White)
                        }
                    }
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("الملاحظات", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Top) {
                                OutlinedTextField(newNote, { newNote = it }, Modifier.weight(1f),
                                    placeholder = { Text("أضف ملاحظة...") }, maxLines = 3)
                                IconButton(onClick = {
                                    if (newNote.isNotBlank()) scope.launch {
                                        repo.addNote(customerId, newNote.trim()); newNote = ""
                                    }
                                }, enabled = newNote.isNotBlank()) {
                                    Icon(Icons.Rounded.Add, "إضافة", tint = AppColors.PrimaryBlue)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (notes.isEmpty()) Text("لا توجد ملاحظات", color = AppColors.Gray,
                                style = MaterialTheme.typography.bodySmall)
                            notes.forEach { n ->
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    Text(n.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(Fmt.ts(n.createdAt), style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.Gray)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSchedule) {
        ScheduleDialog(repo, customerId, onDismiss = { showSchedule = false })
    }
}

@Composable
fun InfoRow(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = AppColors.Gray)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

private fun sendReminder(ctx: Context, repo: MainRepo, c: Customer) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val shop = repo.db.shopDao().get()
        val due = repo.db.dueDao().all().firstOrNull()?.firstOrNull { it.due.customerId == c.id }
        val msg = ReminderBuilder.build(
            shop = shop?.name ?: "المحل",
            customer = c.name,
            balance = c.balance,
            date = due?.due?.date ?: "حسب الاتفاق",
            time = due?.due?.time ?: "",
            phone = shop?.phone ?: "",
            address = shop?.address ?: "")
        val phone = DataPreservation.parsePhone(c.phone)
        if (phone != null) {
            val url = "https://wa.me/$phone?text=" + URLEncoder.encode(msg, "UTF-8")
            val ok = runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            }.isSuccess
            if (!ok) share(ctx, msg)
        } else share(ctx, msg)
    }
}

private fun share(ctx: Context, msg: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, msg) }
    runCatching { ctx.startActivity(Intent.createChooser(i, "إرسال تذكير")) }
}

@Composable
fun ScheduleDialog(repo: MainRepo, customerId: Long, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(Fmt.today()) }
    var time by remember { mutableStateOf("10:00") }
    var beforeDay by remember { mutableStateOf(true) }
    var atTime by remember { mutableStateOf(true) }
    var after1 by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تحديد موعد استحقاق") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(date, { date = it }, label = { Text("التاريخ (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("الوقت (HH:MM)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(beforeDay, { beforeDay = it }); Text("تذكير قبل الموعد بيوم")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(atTime, { atTime = it }); Text("تنبيه في وقت الاستحقاق")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(after1, { after1 = it }); Text("متابعة بعد الموعد بيوم")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                scope.launch {
                    repo.saveDue(DueDate(customerId = customerId, date = date.trim(), time = time.trim(),
                        remBeforeDay = if (beforeDay) 1 else 0,
                        remAt = if (atTime) 1 else 0,
                        remAfter1 = if (after1) 1 else 0))
                    onDismiss()
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                Text("حفظ الموعد", color = Color.White)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
