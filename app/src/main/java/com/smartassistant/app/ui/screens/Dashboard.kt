package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartassistant.app.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dashboard() {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("المساعد الذكي", color = Color.White) },
            navigationIcon = { IconButton({}) { Icon(Icons.Rounded.Menu, "القائمة", tint = Color.White) } },
            actions = { IconButton({}) { Icon(Icons.Rounded.Notifications, "الإشعارات", tint = Color.White) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)) {
            Text("مرحباً بك 👋", style = MaterialTheme.typography.headlineMedium)
            Text("إليك نظرة عامة على نشاطك اليوم", color = AppColors.Gray)
        }
    }
}
