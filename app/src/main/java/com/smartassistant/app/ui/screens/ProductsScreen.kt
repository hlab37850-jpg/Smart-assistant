package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.smartassistant.app.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الأصناف والمخزون", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { }, containerColor = AppColors.PrimaryBlue) {
                Icon(Icons.Rounded.Add, "إضافة صنف", tint = Color.White)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("شاشة الأصناف — قريباً", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

cat > $J/ui/screens/ReportsScreen.kt <<'EOF'
package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.smartassistant.app.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("التقارير", color = Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Text("شاشة التقارير — قريباً", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
