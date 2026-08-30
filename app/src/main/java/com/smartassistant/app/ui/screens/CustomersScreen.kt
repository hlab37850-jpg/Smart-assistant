package com.smartassistant.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PeopleOutline
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
fun CustomersScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("ALL") }

    val pagingSource = remember(query, filter) { repo.customersPaged(query, filter) }
    val pager = remember(pagingSource) {
        Pager(config = PagingConfig(pageSize = 20, enablePlaceholders = false)) { pagingSource }
    }.flow.collectAsLazyPagingItems()

    val currentItems = remember(pager.itemCount, pager.loadState) {
        (0 until pager.itemCount).mapNotNull { pager[it] }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("العملاء", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate(Routes.ADD_CUSTOMER) },
                containerColor = AppColors.PrimaryBlue) {
                Icon(Icons.Rounded.Add, contentDescription = "إضافة عميل", tint = Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("ابحث عن عميل...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true)

            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "الكل", "DEBT" to "عليهم", "CREDIT" to "لهم",
                    "OVERDUE" to "المتأخرون", "TODAY" to "اليوم", "FORGOTTEN" to "المنسيون"
                ).forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryBlue,
                            selectedLabelColor = Color.White))
                }
            }

            when {
                pager.loadState.refresh is LoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                pager.itemCount == 0 && pager.loadState.refresh is LoadState.NotLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.PeopleOutline, contentDescription = null,
                                tint = AppColors.Gray, modifier = Modifier.size(64.dp))
                            Text("لا يوجد عملاء بعد", color = AppColors.Gray)
                            Button(onClick = { nav.navigate(Routes.ADD_CUSTOMER) },
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                                Text("إضافة عميل", color = Color.White)
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(currentItems, key = { it.customer.id }) { row ->
                            CustomerCard(
                                name = row.customer.name,
                                phone = row.customer.phone ?: "—",
                                balance = row.customer.balance,
                                dueDate = row.dueDate,
                                dueTime = row.dueTime,
                                onClick = { nav.navigate(Routes.customerDetail(row.customer.id)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomerCard(name: String, phone: String, balance: Double,
                 dueDate: String?, dueTime: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(phone, style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                }
                Text(Fmt.money(balance), style = MaterialTheme.typography.titleLarge,
                    color = if (balance > 0) AppColors.RedDanger else AppColors.GreenSuccess)
            }
            if (dueDate != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الاستحقاق:", style = MaterialTheme.typography.bodySmall, color = AppColors.Gray)
                    Text("$dueDate ${dueTime ?: ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
