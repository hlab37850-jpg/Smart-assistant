package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.room.withTransaction
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.local.entity.ImportRawRow
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.SessionStatus
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
    var session by remember { mutableStateOf<com.smartassistant.app.data.local.entity.ImportSession?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            rows = repo.db.importDao().rowsSync(sessionId)
            session = repo.db.importDao().sessionById(sessionId)
        }
    }

    LaunchedEffect(Unit) { reload() }

    val dataRows = rows.filter { it.status != "TOTAL_ROW" && it.status != "SKIPPED" }
    val approvedCount = dataRows.count { it.approved == 1 }
    val totalCredit = dataRows.sumOf { it.credit }
    val totalDebit = dataRows.sumOf { it.debit }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("مراجعة الاستيراد", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.DeepBlue)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "سجلات: §{dataRows.size} | سليم: §{dataRows.count { it.status == "VALID" }} | مراجعة: §{dataRows.count { it.status == "WARNING" }}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            "المستخرج — لك: §{Fmt.money(totalCredit)} | عليك: §{Fmt.money(totalDebit)}",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall
                        )
                        msg?.let {
                            Text(it, color = AppColors.GoldAccent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                dataRows.filter { it.status == "VALID" && it.approved == 0 }.forEach {
                                    repo.db.importDao().updateRow(it.copy(approved = 1))
                                }
                                reload()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.GreenSuccess)
                    ) {
                        Text("اعتماد كل السليم", color = Color.White)
                    }

                    Button(
                        enabled = approvedCount > 0 && !busy,
                        onClick = {
                            scope.launch {
                                val approved = dataRows.filter { it.approved == 1 }
                                if (approved.isEmpty()) {
                                    msg = "لا توجد سجلات معتمدة"
                                    return@launch
                                }

                                busy = true
                                msg = null

                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        repo.createBackup(true)
                                        val kind = session?.kind ?: ImportKind.CUSTOMER.name

                                        repo.db.withTransaction {
                                            approved.forEach { r ->
                                                if (kind == ImportKind.PRODUCT.name) {
                                                    repo.upsertProduct(
                                                        Product(
                                                            nameRaw = r.nameRaw,
                                                            unit = r.currency
                                                        ),
                                                        r.debit
                                                    )
                                                } else {
                                                    val existing = repo.db.customerDao()
                                                        .byNormalizedName(r.nameNormalized)
                                                    val net = r.debit - r.credit

                                                    if (existing != null) {
                                                        repo.db.customerDao().update(
                                                            existing.copy(
                                                                balance = net,
                                                                debit = r.debit,
                                                                credit = r.credit,
                                                                phone = r.phone ?: existing.phone,
                                                                currency = r.currency ?: existing.currency,
                                                                importSessionId = sessionId,
                                                                updatedAt = System.currentTimeMillis()
                                                            )
                                                        )
                                                    } else {
                                                        repo.db.customerDao().insert(
                                                            Customer(
                                                                name = r.nameDisplay,
                                                                nameNormalized = r.nameNormalized,
                                                                phone = r.phone,
                                                                balance = net,
                                                                rawBalance = r.nameRaw,
                                                                debit = r.debit,
                                                                credit = r.credit,
                                                                currency = r.currency,
                                                                importSessionId = sessionId
                                                            )
                                                        )
                                                    }
                                                }
                                            }

                                            val current = repo.db.importDao().sessionById(sessionId)
                                            if (current != null) {
                                                repo.db.importDao().updateSession(
                                                    current.copy(
                                                        status = SessionStatus.COMPLETED.name,
                                                        finishedAt = System.currentTimeMillis(),
                                                        validCount = approved.size,
                                                        totalCredit = approved.sumOf { it.credit },
                                                        totalDebit = approved.sumOf { it.debit },
                                                        net = approved.sumOf { it.debit - it.credit }
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }

                                result.fold(
                                    onSuccess = {
                                        msg = "تم الاستيراد ✔ (§{approved.size} سجل)"
                                        reload()
                                    },
                                    onFailure = {
                                        msg = "فشل الاستيراد: §{it.message ?: "خطأ غير معروف"}"
                                    }
                                )
                                busy = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)
                    ) {
                        Text("استيراد المعتمد (§{approvedCount})", color = Color.White)
                    }
                }
            }

            items(dataRows.take(200)) { r ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = r.approved == 1,
                            enabled = !busy,
                            onCheckedChange = { v ->
                                scope.launch {
                                    repo.db.importDao().updateRow(r.copy(approved = if (v) 1 else 0))
                                    reload()
                                }
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(r.nameDisplay, style = MaterialTheme.typography.bodyLarge)
                            if (!r.phone.isNullOrBlank()) {
                                Text(r.phone!!, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                            }
                            Text(
                                "لك: §{Fmt.money(r.credit)} | عليك: §{Fmt.money(r.debit)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Gray
                            )
                        }
                        Text(
                            Fmt.money(r.net),
                            color = if (r.net > 0) AppColors.RedDanger else AppColors.GreenSuccess,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                }
            }
        }
    }
}
