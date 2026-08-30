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
import com.smartassistant.app.data.local.entity.Customer
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.ui.theme.AppColors
import com.smartassistant.app.util.DataPreservation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditCustomerScreen(nav: NavController, customerId: Long?) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var balance by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val isEdit = (customerId ?: 0L) > 0

    LaunchedEffect(customerId) {
        if (isEdit) {
            val c = repo.customer(customerId!!)
            if (c != null) {
                name = c.name; phone = c.phone ?: ""; code = c.code ?: ""
                address = c.address ?: ""; balance = c.rawBalance ?: c.balance.toString()
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (isEdit) "تعديل عميل" else "إضافة عميل", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم العميل *") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(code, { code = it }, label = { Text("الكود") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(address, { address = it }, label = { Text("العنوان") },
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(balance, { balance = it }, label = { Text("الرصيد (موجب = عليه، سالب = له)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)

            Button(onClick = {
                scope.launch {
                    loading = true
                    val parsed = DataPreservation.parseAmount(balance)
                    val c = Customer(
                        id = if (isEdit) customerId!! else 0L,
                        name = DataPreservation.keepRaw(name),
                        phone = DataPreservation.parsePhone(phone),
                        code = DataPreservation.keepRaw(code).ifEmpty { null },
                        address = DataPreservation.keepRaw(address).ifEmpty { null },
                        balance = parsed.value ?: 0.0,
                        rawBalance = parsed.raw)
                    repo.saveCustomer(c)
                    loading = false
                    nav.popBackStack()
                }
            }, Modifier.fillMaxWidth().height(56.dp),
                enabled = name.isNotBlank() && !loading,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (isEdit) "حفظ التعديلات" else "إضافة العميل", color = Color.White)
            }
        }
    }
}
