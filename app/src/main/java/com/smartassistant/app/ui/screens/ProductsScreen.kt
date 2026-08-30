package com.smartassistant.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("ALL") }

    val pagingSource = remember(query, tab) { repo.productsPaged(query, tab) }
    val pager = remember(pagingSource) {
        Pager(config = PagingConfig(pageSize = 20, enablePlaceholders = false)) { pagingSource }
    }.flow.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("الأصناف والمخزون", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.ADD_PRODUCT) },
                containerColor = AppColors.PrimaryBlue) {
                Icon(Icons.Rounded.Add, contentDescription = "إضافة صنف", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("ابحث عن صنف...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }, singleLine = true)

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "كل الأصناف", "LOW" to "منخفضة", "OUT" to "ناقصة", "EXP" to "منتهية"
                ).forEach { (key, label) ->
                    FilterChip(selected = tab == key, onClick = { tab = key }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryBlue,
                            selectedLabelColor = Color.White))
                }
            }

            when {
                pager.loadState.refresh is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                pager.itemCount == 0 -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Inventory, contentDescription = null,
                                tint = AppColors.Gray, modifier = Modifier.size(64.dp))
                            Text("لا توجد أصناف بعد", color = AppColors.Gray)
                            Button(onClick = { nav.navigate(Routes.ADD_PRODUCT) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                                Text("إضافة صنف", color = Color.White)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(
                            count = pager.itemCount,
                            key = { index -> pager.peek(index)?.product?.id ?: index }
                        ) { index ->
                            val row = pager[index]
                            if (row != null) {
                                ProductCard(
                                    name = row.product.nameRaw,
                                    code = row.product.code ?: "—",
                                    qty = row.qty ?: 0.0,
                                    minQty = row.minQty ?: 0.0,
                                    onClick = { nav.navigate(Routes.productDetail(row.product.id)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProductCard(name: String, code: String, qty: Double, minQty: Double, onClick: () -> Unit) {
    val status = when {
        qty <= 0 -> "ناقص" to AppColors.RedDanger
        qty <= minQty -> "منخفض" to AppColors.GoldAccent
        else -> "متوفر" to AppColors.GreenSuccess
    }
    Card(Modifier.fillMaxWidth(), onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(code, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                }
                Surface(color = status.second.copy(alpha = 0.1f), shape = MaterialTheme.shapes.small) {
                    Text(status.first, color = status.second,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("الكمية: ${Fmt.money(qty)}", style = MaterialTheme.typography.bodyMedium)
                Text("الحد: ${Fmt.money(minQty)}", style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
            }
        }
    }
}
