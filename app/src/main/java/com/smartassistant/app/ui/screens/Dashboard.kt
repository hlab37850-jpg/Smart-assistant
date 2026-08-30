package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val shop by repo.db.shopDao().observe().collectAsState(initial = null)
    val customersCount by repo.db.customerDao().count().collectAsState(initial = 0)
    val theyOwe by repo.db.customerDao().totalTheyOwe().collectAsState(initial = 0.0)
    val weOwe by repo.db.customerDao().totalWeOwe().collectAsState(initial = 0.0)
    val productsCount by repo.db.productDao().count().collectAsState(initial = 0)
    val lowStockCount by repo.db.productDao().lowOrOutCount().collectAsState(initial = 0)
    val todayDues by repo.db.dueDao().todayCount(Fmt.today()).collectAsState(initial = 0)
    val upcoming by repo.upcoming.collectAsState(initial = emptyList())
    val lowStock by repo.lowStock.collectAsState(initial = emptyList())
    val recentActivity by repo.recentActivity.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shop?.name ?: "المساعد الذكي", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Rounded.Menu, "القائمة", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.NOTIFICATIONS) }) {
                        Icon(Icons.Rounded.Notifications, "الإشعارات", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.ADD_CUSTOMER) },
                containerColor = AppColors.PrimaryBlue) {
                Icon(Icons.Rounded.Add, "إضافة عميل", tint = Color.White)
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("مرحباً بك 👋", style = MaterialTheme.typography.headlineMedium)
                    Text("إليك نظرة عامة على نشاطك اليوم", color = AppColors.Gray)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), Icons.Rounded.People, AppColors.PrimaryBlue,
                        "العملاء", "$customersCount", "إجمالي العملاء") { nav.navigate(Routes.CUSTOMERS) }
                    StatCard(Modifier.weight(1f), Icons.Rounded.AccountBalance, AppColors.GreenSuccess,
                        "لهم", Fmt.money(theyOwe), "إجمالي الأرصدة") { nav.navigate(Routes.REPORTS) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), Icons.Rounded.Schedule, AppColors.GoldAccent,
                        "استحقاقات اليوم", "$todayDues", "استحقاق") { nav.navigate(Routes.DUES) }
                    StatCard(Modifier.weight(1f), Icons.Rounded.Warning, AppColors.RedDanger,
                        "نواقص", "$lowStockCount", "منخفض/ناقص") { nav.navigate(Routes.PRODUCTS) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(Modifier.weight(1f), Icons.Rounded.Inventory, AppColors.CyanAccent,
                        "الأصناف", "$productsCount", "إجمالي الأصناف") { nav.navigate(Routes.PRODUCTS) }
                    StatCard(Modifier.weight(1f), Icons.Rounded.TrendingDown, AppColors.RedDanger,
                        "عليهم", Fmt.money(weOwe), "إجمالي الأرصدة") { nav.navigate(Routes.REPORTS) }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("نظرة عامة على الأرصدة", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(Modifier.size(12.dp), shape = MaterialTheme.shapes.small,
                                        color = AppColors.GreenSuccess) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text("لهم: ${Fmt.money(theyOwe)}", style = MaterialTheme.typography.bodyLarge)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(Modifier.size(12.dp), shape = MaterialTheme.shapes.small,
                                        color = AppColors.RedDanger) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text("عليهم: ${Fmt.money(weOwe)}", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    progress = { if (theyOwe + weOwe > 0) theyOwe / (theyOwe + weOwe) else 0.5f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = AppColors.GreenSuccess, trackColor = AppColors.RedDanger,
                                    strokeWidth = 14.dp)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("الإجمالي", style = MaterialTheme.typography.labelSmall)
                                    Text(Fmt.money(theyOwe + weOwe), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("أقرب الاستحقاقات", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        if (upcoming.isEmpty()) Text("لا توجد استحقاقات قادمة", color = AppColors.Gray)
                        upcoming.forEach { d ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.customerName, style = MaterialTheme.typography.bodyLarge)
                                    Text("${d.due.date} ${d.due.time}", style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.Gray)
                                }
                                Text(Fmt.money(d.balance), style = MaterialTheme.typography.titleSmall,
                                    color = if (d.balance > 0) AppColors.RedDanger else AppColors.GreenSuccess)
                            }
                        }
                        TextButton(onClick = { nav.navigate(Routes.DUES) }) { Text("عرض الكل") }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("تنبيهات المخزون", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        if (lowStock.isEmpty()) Text("المخزون بحالة جيدة", color = AppColors.GreenSuccess)
                        lowStock.forEach { p ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.product.nameRaw, style = MaterialTheme.typography.bodyLarge)
                                    Text("الكمية: ${Fmt.money(p.qty ?: 0.0)} / الحد: ${Fmt.money(p.minQty ?: 0.0)}",
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                                }
                                Surface(color = AppColors.RedDanger.copy(alpha = 0.1f),
                                    shape = MaterialTheme.shapes.small) {
                                    Text(if ((p.qty ?: 0.0) <= 0) "ناقص" else "منخفض",
                                        color = AppColors.RedDanger,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(20.dp)) {
                        Text("آخر النشاطات", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        if (recentActivity.isEmpty()) Text("لا توجد نشاطات بعد", color = AppColors.Gray,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        recentActivity.forEach { l ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(l.action, style = MaterialTheme.typography.bodyLarge)
                                    Text(l.details, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                                }
                                Text(Fmt.ts(l.createdAt), style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.Gray)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
fun StatCard(modifier: Modifier, icon: ImageVector, iconColor: Color,
             title: String, value: String, description: String, onClick: () -> Unit) {
    Card(modifier = modifier, onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Surface(Modifier.size(40.dp), shape = MaterialTheme.shapes.medium,
                color = iconColor.copy(alpha = 0.1f)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = AppColors.Gray)
            Text(value, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
        }
    }
}
