package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.normalizers.ArabicNormalizer
import com.smartassistant.app.importer.normalizers.NumberParser
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(nav: NavController, sessionId: Long) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ImportRawRow>>(emptyList()) }
    var editRow by remember { mutableStateOf<ImportRawRow?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun reload() { scope.launch { rows = repo.db.importDao().rowsSync(sessionId) } }
    LaunchedEffect(Unit) { reload() }

    val dataRows = rows.filter { it.status != "TOTAL_ROW" && it.status != "SKIPPED" }
    val totalRows = rows.filter { it.status == "TOTAL_ROW" }
    val pdfCredit = totalRows.sumOf { it.credit }
    val pdfDebit = totalRows.sumOf { it.debit }
    val extCredit = dataRows.sumOf { it.credit }
    val extDebit = dataRows.sumOf { it.debit }
    val diffC = kotlin.math.abs(pdfCredit - extCredit)
    val diffD = kotlin.math.abs(pdfDebit - extDebit)
    val validated = totalRows.isEmpty() || (diffC < 1.0 && diffD < 1.0)
    val approvedCount = dataRows.count { it.approved == 1 }
    val reviewCount = dataRows.count { it.status == "WARNING" }

    fun doImport() {
        scope.launch {
            val approved = dataRows.filter { it.approved == 1 }
            if (approved.isEmpty()) { msg = "لا توجد سجلات معتمدة للاستيراد"; return@launch }
            if (!validated) { msg = "التحقق المحاسبي غير مطابق — عالج الفرق أو احذف صفوف الإجماليات"; return@launch }
            withContext(Dispatchers.IO) {
                runCatching {
                    repo.createBackup(true)
                    repo.db.runInTransaction {
                        approved.forEach { row ->
                            val net = row.debit - row.credit
                            val existing = repo.db.customerDao().byNormalizedName(row.nameNormalized)
                            if (existing != null) {
                                repo.db.customerDao().update(existing.copy(
                                    balance = net, debit = row.debit, credit = row.credit,
                                    currency = row.currency ?: existing.currency,
                                    sourcePage = row.pageNumber, importSessionId = sessionId,
                                    updatedAt = System.currentTimeMillis()))
                            } else {
                                repo.db.customerDao().insert(Customer(
                                    name = row.nameDisplay, nameNormalized = row.nameNormalized,
                                    balance = net, rawBalance = row.nameRaw,
                                    debit = row.debit, credit = row.credit, currency = row.currency,
                                    sourcePage = row.pageNumber, importSessionId = sessionId))
                            }
                        }
                    }
                    val s = repo.db.importDao().sessionById(sessionId)
                    if (s != null) repo.db.importDao().updateSession(s.copy(
                        status = "COMPLETED", finishedAt = System.currentTimeMillis(),
                        validCount = approved.size, reviewCount = reviewCount,
                        totalCredit = extCredit, totalDebit = extDebit, net = extDebit - extCredit))
                }.onFailure { msg = "فشل الاستيراد: ${it.message}" }
            }
            msg = "تم الاستيراد ✔"
            nav.popBackStack()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("مراجعة الاستيراد", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { msg?.let { Text(it, color = AppColors.GoldAccent) } }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("سجلات: ${dataRows.size} | سليم: ${dataRows.count { it.status == "VALID" }} | مراجعة: $reviewCount",
                            style = MaterialTheme.typography.titleSmall)
                        Text("المستخرج — لك: ${Fmt.money(extCredit)} | عليك: ${Fmt.money(extDebit)}",
                            style = MaterialTheme.typography.bodySmall)
                        if (totalRows.isNotEmpty()) {
                            Text("إجماليات PDF — لك: ${Fmt.money(pdfCredit)} | عليك: ${Fmt.money(pdfDebit)}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(if (validated) "التحقق المحاسبي: مطابق ✅ (فرق 0)"
                                 else "التحقق المحاسبي: غير مطابق ⚠️ فرق لك ${Fmt.money(diffC)} / عليك ${Fmt.money(diffD)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (validated) AppColors.GreenSuccess else AppColors.RedDanger)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            dataRows.filter { it.status == "VALID" && it.approved == 0 }.forEach {
                                repo.db.importDao().updateRow(it.copy(approved = 1))
                            }
                            reload()
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.GreenSuccess)) {
                        Text("اعتماد كل السليم", color = Color.White)
                    }
                    Button(onClick = { doImport() }, enabled = approvedCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                        Text("استيراد المعتمد ($approvedCount)", color = Color.White)
                    }
                }
            }
            items(dataRows, key = { it.id }) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.approved == 1, onCheckedChange = { v ->
                                scope.launch { repo.db.importDao().updateRow(row.copy(approved = if (v) 1 else 0)); reload() }
                            })
                            Column(Modifier.weight(1f)) {
                                Text(row.nameDisplay, style = MaterialTheme.typography.bodyLarge)
                                Text("لك: ${Fmt.money(row.credit)} | عليك: ${Fmt.money(row.debit)} | ص${row.pageNumber} | ثقة ${row.confidenceScore}%",
                                    style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                            }
                            Text(Fmt.money(row.net), color = if (row.net > 0) AppColors.RedDanger else AppColors.GreenSuccess,
                                style = MaterialTheme.typography.titleSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row.status == "WARNING") Surface(color = AppColors.GoldAccent.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
                                Text("يحتاج مراجعة", color = AppColors.GoldAccent,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { editRow = row }) { Icon(Icons.Rounded.Edit, "تعديل", tint = AppColors.PrimaryBlue) }
                            IconButton(onClick = {
                                scope.launch { repo.db.importDao().updateRow(row.copy(status = "SKIPPED")); reload() }
                            }) { Icon(Icons.Rounded.Delete, "تجاهل", tint = AppColors.RedDanger) }
                        }
                    }
                }
            }
        }
    }

    editRow?.let { r -> EditRowDialog(r, onDismiss = { editRow = null }, onSave = { updated ->
        scope.launch { repo.db.importDao().updateRow(updated); reload(); editRow = null }
    }) }
}

@Composable
fun EditRowDialog(row: ImportRawRow, onDismiss: () -> Unit, onSave: (ImportRawRow) -> Unit) {
    var name by remember { mutableStateOf(row.nameDisplay) }
    var credit by remember { mutableStateOf(row.credit.toString()) }
    var debit by remember { mutableStateOf(row.debit.toString()) }
    var currency by remember { mutableStateOf(row.currency ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل السجل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(credit, { credit = it }, label = { Text("لك (دائن)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(debit, { debit = it }, label = { Text("عليك (مدين)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(currency, { currency = it }, label = { Text("العملة") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                val t = ArabicNormalizer.process(name)
                val c = NumberParser.parse(credit).value ?: row.credit
                val d = NumberParser.parse(debit).value ?: row.debit
                onSave(row.copy(nameRaw = t.raw, nameDisplay = t.display, nameNormalized = t.normalized,
                    credit = c, debit = d, net = d - c, currency = currency.ifEmpty { null },
                    userEdited = 1, approved = 1))
            }) { Text("حفظ واعتماد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}
