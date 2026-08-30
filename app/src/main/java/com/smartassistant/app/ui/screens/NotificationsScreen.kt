package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    val list by repo.notifications.collectAsState(initial = emptyList())

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الإشعارات", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.NotificationsActive, null, tint = AppColors.Gray,
                        modifier = Modifier.size(64.dp))
                    Text("لا توجد إشعارات", color = AppColors.Gray)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list.size) { i ->
                    val n = list[i]
                    Card(colors = CardDefaults.cardColors(
                        containerColor = if (n.read == 0) AppColors.PrimaryBlue.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surface),
                        onClick = {
                            scope.launch { repo.markRead(n.id) }
                            when {
                                n.route == "dues" -> nav.navigate(Routes.DUES)
                                n.route.startsWith("customer/") ->
                                    nav.navigate(Routes.customerDetail(n.route.removePrefix("customer/").toLongOrNull() ?: 0L))
                            }
                        }) {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(n.title, style = MaterialTheme.typography.titleSmall)
                                Text(Fmt.ts(n.createdAt), style = MaterialTheme.typography.labelSmall, color = AppColors.Gray)
                            }
                            Text(n.body, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
