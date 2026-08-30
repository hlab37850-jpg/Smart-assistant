package com.smartassistant.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.AIMessage
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.DataPreservation
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<AIMessage>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var convId by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        val conv = repo.db.aiDao().lastConv()
        if (conv != null) { convId = conv.id; repo.db.aiDao().messages(conv.id).collect { messages = it } }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("المساعد الذكي", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.PurpleAI))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Bubble("assistant", "مرحباً بك في المساعد الذكي 🤖\nكيف يمكنني مساعدتك اليوم؟")
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("من هم العملاء المتأخرون؟", "ما الأصناف الناقصة؟", "أنشئ تقريراً").forEach { s ->
                            SuggestionChip(onClick = { input = s }, label = { Text(s, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                }
                items(messages.size) { i ->
                    Bubble(messages[i].role, messages[i].content)
                }
                if (thinking) item { Bubble("assistant", "⏳ جاري التفكير...") }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f),
                    placeholder = { Text("اكتب سؤالك...") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(onClick = {
                    if (input.isBlank() || thinking) return@FloatingActionButton
                    val q = input.trim(); input = ""
                    scope.launch {
                        thinking = true
                        if (convId < 0) convId = repo.db.aiDao().conv(
                            com.smartassistant.app.data.local.entity.AIConversation(title = q.take(30)))
                        repo.db.aiDao().msg(AIMessage(conversationId = convId, role = "user", content = q))
                        val answer = LocalAssistant.answer(repo, q)
                        repo.db.aiDao().msg(AIMessage(conversationId = convId, role = "assistant", content = answer))
                        thinking = false
                    }
                }, containerColor = AppColors.PurpleAI, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "إرسال", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
fun Bubble(role: String, text: String) {
    val isUser = role == "user"
    Box(Modifier.fillMaxWidth(), contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(shape = RoundedCornerShape(16.dp),
            color = if (isUser) AppColors.PrimaryBlue.copy(alpha = 0.15f) else AppColors.PurpleAI.copy(alpha = 0.15f)) {
            Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

object LocalAssistant {
    suspend fun answer(repo: MainRepo, q: String): String {
        val t = q.replace(Regex("[أإآ]"), "ا").replace("ة", "ه").replace(Regex("\\s+"), " ").trim()
        return when {
            t.contains("متاخر") || t.contains("تاخر") -> {
                val list = firstOrNull(repo.dues("OVERDUE")) ?: emptyList()
                if (list.isEmpty()) "لا يوجد عملاء متأخرون 🎉"
                else "العملاء المتأخرون (${list.size}):\n" + list.take(7).joinToString("\n") {
                    "• ${it.customerName} — ${Fmt.money(it.balance)} — منذ ${Fmt.daysSince(it.due.date)} يوم"
                }
            }
            t.contains("ناقص") || t.contains("نواقص") || t.contains("منخفض") -> {
                val list = firstOrNull(repo.lowStock) ?: emptyList()
                if (list.isEmpty()) "المخزون بحالة جيدة ✅"
                else "الأصناف الناقصة/المنخفضة (${list.size}):\n" + list.joinToString("\n") {
                    "• ${it.product.nameRaw} — الكمية: ${Fmt.money(it.qty ?: 0.0)}"
                }
            }
            t.contains("منسي") -> {
                val list = firstOrNull(repo.forgotten) ?: emptyList()
                if (list.isEmpty()) "لا يوجد عملاء منسيون ✅"
                else "العملاء المنسيون (${list.size}):\n" + list.take(7).joinToString("\n") { "• ${it.customerName}" }
            }
            t.contains("تقرير") || t.contains("ملخص") -> {
                val cs = repo.allCustomers()
                val they = cs.filter { it.balance > 0 }.sumOf { it.balance }
                val we = cs.filter { it.balance < 0 }.sumOf { -it.balance }
                "📊 ملخص سريع:\n• العملاء: ${cs.size}\n• عليهم: ${Fmt.money(they)}\n• لهم: ${Fmt.money(we)}\n• الأصناف: ${repo.db.productDao().allSync().size}"
            }
            t.contains("تذكير") || t.contains("رساله") -> {
                val name = t.substringAfter("لـ", "").substringAfter("للعميل", "").trim()
                val c = repo.allCustomers().firstOrNull { DataPreservation.normalizeForMatch(it.name).contains(DataPreservation.normalizeForMatch(name)) && name.isNotEmpty() }
                if (c == null) "اذكر اسم العميل لأكتب لك رسالة التذكير، مثال: اكتب رسالة تذكير لـ أحمد"
                else com.smartassistant.app.notifications.ReminderBuilder.build(
                    repo.db.shopDao().get()?.name ?: "المحل", c.name, c.balance,
                    "حسب الاتفاق", "", repo.db.shopDao().get()?.phone ?: "", repo.db.shopDao().get()?.address ?: "")
            }
            else -> "يمكنني مساعدتك في:\n• من هم العملاء المتأخرون؟\n• ما الأصناف الناقصة؟\n• العملاء المنسيون\n• أنشئ تقريراً\n• اكتب رسالة تذكير لـ [اسم العميل]"
        }
    }
}
