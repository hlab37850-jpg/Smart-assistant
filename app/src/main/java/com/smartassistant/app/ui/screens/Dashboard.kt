package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.ShopSettings
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard(navController: NavController) {
    val ctx = LocalContext.current
    val repo = androidx.compose.runtime.remember { MainRepo(ctx) }
    val shop by repo.db.shopDao().observe().collectAsState(initial = null)
    val customersCount by repo.db.customerDao().count().collectAsState(initial = 0)
    val theyOwe by repo.db.customerDao().totalTheyOwe().collectAsState(initial = 0.0)
    val weOwe by repo.db.customerDao().totalWeOwe().collectAsState(initial = 0.0)
    val productsCount by repo.db.productDao().count().collectAsState(initial = 0)
    val lowStockCount by repo.db.productDao().lowOrOutCount().collectAsState(initial = 0)
    val todayDues by repo.db.dueDao().todayCount(Fmt.today()).collectAsState(initial = 0)
    val recentActivity by repo.recentActivity.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shop?.name ?: "المساعد الذكي", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Menu, "القائمة", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Notifications, "الإشعارات", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("مرحباً بك 👋", style = MaterialTheme.typography.headlineMedium)
                    Text("إليك نظرة عامة على نشاطك اليوم", color = AppColors.Gray)
                }
            }

            // ===== بطاقات الإحصائيات =====
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.People,
                        iconColor = AppColors.PrimaryBlue,
                        title = "العملاء",
                        value = "$customersCount",
                        description = "إجمالي العملاء",
                        onClick = { navController.navigate("customers") }
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.AccountBalance,
                        iconColor = AppColors.GreenSuccess,
                        title = "إجمالي الأرصدة",
                        value = Fmt.money(theyOwe),
                        description = "لهم",
                        onClick = { }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Schedule,
                        iconColor = AppColors.GoldAccent,
                        title = "استحقاقات اليوم",
                        value = "$todayDues",
                        description = "استحقاق اليوم",
                        onClick = { }
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Warning,
                        iconColor = AppColors.RedDanger,
                        title = "الأصناف الناقصة",
                        value = "$lowStockCount",
                        description = "منخفضة/ناقصة",
                        onClick = { }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.Inventory,
                        iconColor = AppColors.CyanAccent,
                        title = "الأصناف",
                        value = "$productsCount",
                        description = "إجمالي الأصناف",
                        onClick = { navController.navigate("products") }
                    )
                    StatCard(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Rounded.TrendingDown,
                        iconColor = AppColors.RedDanger,
                        title = "علينا",
                        value = Fmt.money(weOwe),
                        description = "إجمالي ما علينا",
                        onClick = { }
                    )
                }
            }

            // ===== بطاقة نظرة عامة على الأرصدة (Donut Chart) =====
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("نظرة عامة على الأرصدة", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(12.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = AppColors.GreenSuccess
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text("لهم: ${Fmt.money(theyOwe)}", style = MaterialTheme.typography.bodyLarge)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        modifier = Modifier.size(12.dp),
                                        shape = MaterialTheme.shapes.small,
                                        color = AppColors.RedDanger
                                    ) {}
                                    Spacer(Modifier.width(8.dp))
                                    Text("عليهم: ${Fmt.money(weOwe)}", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            Box(
                                modifier = Modifier.size(100.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    progress = { if (theyOwe + weOwe > 0) theyOwe / (theyOwe + weOwe) else 0.5f },
                                    modifier = Modifier.fillMaxSize(),
                                    color = AppColors.GreenSuccess,
                                    trackColor = AppColors.RedDanger,
                                    strokeWidth = 12.dp
                                )
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("الإجمالي", style = MaterialTheme.typography.labelSmall)
                                    Text(Fmt.money(theyOwe + weOwe), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }
                    }
                }
            }

            // ===== آخر النشاطات =====
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("آخر النشاطات", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(12.dp))
                        if (recentActivity.isEmpty()) {
                            Text("لا توجد نشاطات بعد", color = AppColors.Gray, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        } else {
                            recentActivity.take(5).forEach { log ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(log.action, style = MaterialTheme.typography.bodyLarge)
                                        Text(log.details, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                                    }
                                    Text(Fmt.ts(log.createdAt), style = MaterialTheme.typography.labelSmall, color = AppColors.Gray)
                                }
                                if (log != recentActivity.last()) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    iconColor: Color,
    title: String,
    value: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = MaterialTheme.shapes.medium,
                color = iconColor.copy(alpha = 0.1f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = AppColors.Gray)
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
        }
    }
}
