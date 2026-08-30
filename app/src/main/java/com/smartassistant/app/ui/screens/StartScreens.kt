package com.smartassistant.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.smartassistant.app.data.local.entity.ShopSettings
import com.smartassistant.app.data.prefs.AppPrefs
import com.smartassistant.app.data.repo.ShopRepository
import com.smartassistant.app.ui.theme.AppColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Splash(onFinish: () -> Unit) {
    LaunchedEffect(Unit) { delay(1200); onFinish() }
    Box(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(AppColors.NavyDark, AppColors.DeepBlue))),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = AppColors.DeepBlue,
                modifier = Modifier.size(140.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SmartToy, null, tint = AppColors.PrimaryBlue,
                        modifier = Modifier.size(80.dp))
                }
            }
            Text("المساعد الذكي", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("إدارة ذكية للعملاء والمخزون", color = AppColors.DarkSurfaceText,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Onboarding(onStart: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()
        .background(Brush.verticalGradient(listOf(AppColors.NavyDark, AppColors.DeepBlue))),
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(28.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = AppColors.DeepBlue,
                modifier = Modifier.size(140.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SmartToy, null, tint = AppColors.PrimaryBlue,
                        modifier = Modifier.size(80.dp))
                }
            }
            Text("مرحباً بك في المساعد الذكي", style = MaterialTheme.typography.headlineMedium,
                color = Color.White, textAlign = TextAlign.Center)
            Text("نظام ذكي يساعدك على إدارة العملاء والأصناف والمخزون والاستحقاقات.",
                style = MaterialTheme.typography.bodyLarge, color = AppColors.DarkSurfaceText,
                textAlign = TextAlign.Center)
            Button(onClick = {
                scope.launch { AppPrefs.get(ctx).setOnboardingDone(); onStart() }
            }, Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                Text("ابدأ الآن", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = {
                scope.launch { AppPrefs.get(ctx).setOnboardingDone(); onSkip() }
            }) { Text("تخطي الإعداد", color = AppColors.DarkSurfaceText) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopSetup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { ShopRepository(ctx) }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var logo by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if (it != null) logo = it }

    Scaffold(topBar = {
        TopAppBar(title = { Text("إعداد بيانات المحل", color = Color.White) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.NavyDark))
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(20.dp)
            .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(Modifier.size(120.dp).clip(CircleShape).background(AppColors.DeepBlue),
                    contentAlignment = Alignment.Center) {
                    if (logo == null) Icon(Icons.Rounded.Store, null,
                        tint = AppColors.PrimaryBlue, modifier = Modifier.size(60.dp))
                    else AsyncImage(logo, null, Modifier.size(120.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop)
                }
                FloatingActionButton(onClick = { pick.launch("image/*") }, Modifier.size(42.dp),
                    containerColor = AppColors.PrimaryBlue, shape = CircleShape) {
                    Icon(Icons.Rounded.AddPhotoAlternate, "إضافة الشعار",
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("اسم المحل") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it }, label = { Text("رقم الهاتف") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(whatsapp, { whatsapp = it }, label = { Text("رقم واتساب") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it }, label = { Text("العنوان") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp))
            Button(onClick = {
                scope.launch {
                    repo.save(ShopSettings(name = name.trim(), phone = phone.trim(),
                        whatsapp = whatsapp.trim(), address = address.trim(),
                        logoPath = logo?.toString()))
                    onDone()
                }
            }, Modifier.fillMaxWidth().height(56.dp), enabled = name.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryBlue)) {
                Text("حفظ", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
