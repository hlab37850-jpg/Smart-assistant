package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.Fmt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(nav: NavController, productId: Long) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    var product by remember { mutableStateOf<Product?>(null) }
    var qty by remember { mutableStateOf(0.0) }
    var minQty by remember { mutableStateOf(0.0) }
    var expiry by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(productId) {
        product = repo.product(productId)
        repo.inventoryFlow(productId).collect { inv ->
            qty = inv?.qty ?: 0.0; minQty = inv?.minQty ?: 0.0; expiry = inv?.expiryDate
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(product?.nameRaw ?: "تفاصيل الصنف", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            actions = {
                IconButton(onClick = { nav.navigate(Routes.editProduct(productId)) }) {
                    Icon(Icons.Rounded.Edit, "تعديل", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        val p = product
        if (p == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val status = when {
                qty <= 0 -> "ناقص" to AppColors.RedDanger
                qty <= minQty -> "منخفض" to AppColors.GoldAccent
                else -> "متوفر" to AppColors.GreenSuccess
            }
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoRow("الاسم (خام)", p.nameRaw)
                        InfoRow("الكود", p.code ?: "—")
                        InfoRow("الوحدة", p.unit ?: "—")
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("المخزون", style = MaterialTheme.typography.titleMedium)
                            Surface(color = status.second.copy(alpha = 0.1f), shape = MaterialTheme.shapes.small) {
                                Text(status.first, color = status.second,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        InfoRow("الكمية الحالية", Fmt.money(qty), color = status.second)
                        InfoRow("الحد الأدنى", Fmt.money(minQty))
                        InfoRow("الصلاحية", expiry ?: "—")
                    }
                }
            }
        }
    }
}
