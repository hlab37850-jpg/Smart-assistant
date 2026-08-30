package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.paging.compose.collectAsLazyPagingItems
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomersScreen(navController: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }

    val pagingSource = remember(query, filter) { repo.customersPaged(query, filter) }
    val pager = androidx.paging.Pager(
        config = androidx.paging.PagingConfig(pageSize = 20, enablePlaceholders = false),
        pagingSourceFactory = { pagingSource }
    ).flow.collectAsLazyPagingItems()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("العملاء", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* TODO: إضافة عميل */ },
                containerColor = AppColors.PrimaryBlue
            ) {
                Icon(Icons.Rounded.Add, "إضافة عميل", tint = Color.White)
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search Bar
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("ابحث عن عميل...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true
            )

            // Filter Chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf("ALL" to "الكل", "DEBT" to "عليهم", "CREDIT" to "لهم",
                    "OVERDUE" to "المتأخرون", "TODAY" to "اليوم", "FORGOTTEN" to "المنسيون")
                filters.forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryBlue,
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Customers List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(pager.itemCount) { index ->
                    pager[index]?.let { row ->
                        CustomerCard(
                            name = row.customer.name,
                            phone = row.customer.phone ?: "—",
                            balance = row.customer.balance,
                            dueDate = row.dueDate,
                            dueTime = row.dueTime,
                            onClick = { /* TODO: تفاصيل العميل */ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerCard(
    name: String,
    phone: String,
    balance: Double,
    dueDate: String?,
    dueTime: String?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(phone, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                }
                Text(
                    Fmt.money(balance),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (balance > 0) AppColors.RedDanger else AppColors.GreenSuccess
                )
            }
            if (dueDate != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("الاستحقاق:", style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                    Text("$dueDate $dueTime", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
