package com.smartassistant.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.Category
import com.smartassistant.app.data.local.entity.Product
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.DataPreservation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditProductScreen(nav: NavController, productId: Long?) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("0") }
    var minQty by remember { mutableStateOf("0") }
    var expiry by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf(-1L) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val isEdit = (productId ?: 0L) > 0

    LaunchedEffect(Unit) { repo.categories.collect { categories = it } }
    LaunchedEffect(productId) {
        if (isEdit) {
            val p = repo.product(productId!!)
            if (p != null) {
                name = p.nameRaw; code = p.code ?: ""; unit = p.unit ?: ""
                categoryId = p.categoryId ?: -1L
                val inv = repo.inventoryOnce(productId)
                qty = (inv?.qty ?: 0.0).toString()
                minQty = (inv?.minQty ?: 0.0).toString()
                expiry = inv?.expiryDate ?: ""
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isEdit) "تعديل صنف" else "إضافة صنف", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم الصنف * (يُحفظ كما هو بدون تعديل)") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(code, { code = it }, label = { Text("الكود") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(unit, { unit = it }, label = { Text("الوحدة (قطعة، كرتون، متر...)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            if (categories.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = categories.find { it.id == categoryId }?.name ?: "بدون تصنيف",
                        onValueChange = {}, readOnly = true, label = { Text("التصنيف") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("بدون تصنيف") },
                            onClick = { categoryId = -1L; expanded = false })
                        categories.forEach { cat ->
                            DropdownMenuItem(text = { Text(cat.name) },
                                onClick = { categoryId = cat.id; expanded = false })
                        }
                    }
                }
            }

            Text("المخزون", style = MaterialTheme.typography.titleMedium, color = AppColors.PrimaryBlue)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(qty, { qty = it }, label = { Text("الكمية") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(minQty, { minQty = it }, label = { Text("الحد الأدنى") },
                    modifier = Modifier.weight(1f), singleLine = true)
            }
            OutlinedTextField(expiry, { expiry = it }, label = { Text("تاريخ الصلاحية (اختياري YYYY-MM-DD)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            Button(onClick = {
                scope.launch {
                    loading = true
                    val p = Product(
                        id = if (isEdit) productId!! else 0L,
                        nameRaw = DataPreservation.keepRaw(name),
                        code = DataPreservation.keepRaw(code).ifEmpty { null },
                        categoryId = if (categoryId > 0) categoryId else null,
                        unit = DataPreservation.keepRaw(unit).ifEmpty { null })
                    repo.saveProduct(p, qty.toDoubleOrNull() ?: 0.0,
                        minQty.toDoubleOrNull() ?: 0.0, expiry.trim().ifEmpty { null })
                    loading = false
                    nav.popBackStack()
                }
            }, Modifier.fillMaxWidth().height(56.dp),
                enabled = name.isNotBlank() && !loading,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (isEdit) "حفظ التعديلات" else "إضافة الصنف", color = Color.White)
            }
        }
    }
}
