package com.smartassistant.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.smartassistant.app.data.local.entity.ImportSession
import com.smartassistant.app.data.repo.MainRepo
import com.smartassistant.app.importer.extractors.PdfSmartImporter
import com.smartassistant.app.importer.extractors.deleteSessionData
import com.smartassistant.app.importer.models.ImportKind
import com.smartassistant.app.importer.models.SessionStatus
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(nav: NavController) {
    val ctx = LocalContext.current
    val repo = remember { MainRepo(ctx) }
    val scope = rememberCoroutineScope()

    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKindDialog by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(ImportKind.CUSTOMER) }
    var dupSession by remember { mutableStateOf<ImportSession?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    fun startImport(uri: Uri, kind: ImportKind, force: Boolean) {
        scope.launch {
            error = null
            progress = "جاري نسخ ملف PDF..."

            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val name = runCatching {
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            if (c.moveToFirst()) {
                                c.getString(
                                    c.getColumnIndexOrThrow(
                                        android.provider.OpenableColumns.DISPLAY_NAME
                                    )
                                )
                            } else null
                        }
                    }.getOrNull() ?: "import.pdf"

                    val safeName = if (name.lowercase().endsWith(".pdf")) name else "$name.pdf"
                    val f = File(
                        ctx.cacheDir,
                        System.currentTimeMillis().toString() + "_" + safeName
                    )
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    f to safeName
                }.getOrNull()
            }

            if (file == null) {
                progress = null
                error = "تعذر قراءة ملف PDF."
                return@launch
            }

            val (f, name) = file
            val hash = com.smartassistant.app.importer.ImportEngine.computeHash(f)
            val existing = repo.db.importDao().findByHash(hash)

            if (existing != null && !force) {
                progress = null
                dupSession = existing
                pendingUri = uri
                return@launch
            }

            if (existing != null && force) {
                withContext(Dispatchers.IO) { deleteSessionData(repo, existing.id) }
            }

            val sid = repo.db.importDao().session(
                ImportSession(
                    fileName = name,
                    fileHash = hash,
                    fileType = "pdf",
                    kind = kind.name,
                    status = SessionStatus.PROCESSING.name
                )
            )

            progress = "جاري تحليل PDF بالنص الأصلي ثم OCR العربي عند الحاجة..."

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PdfSmartImporter.parse(
                        context = ctx,
                        file = f,
                        shopName = null,
                        kind = kind,
                        session = sid
                    )
                }.getOrElse {
                    error = it.message ?: "تعذر تحليل ملف PDF."
                    null
                }
            }

            progress = null

            if (result == null) {
                repo.db.importDao().updateSession(
                    repo.db.importDao().sessionById(sid)?.copy(
                        status = SessionStatus.FAILED.name,
                        reviewCount = 0
                    ) ?: return@launch
                )
                return@launch
            }

            val rowsToSave = if (result.rows.isNotEmpty()) {
                result.rows
            } else {
                result.products.mapIndexed { index, pair ->
                    com.smartassistant.app.data.local.entity.ImportRawRow(
                        sessionId = sid,
                        pageNumber = 0,
                        rowNumber = index + 1,
                        nameRaw = pair.first.nameRaw,
                        nameDisplay = pair.first.nameRaw,
                        nameNormalized = com.smartassistant.app.importer.normalizers.ArabicNormalizer
                            .process(pair.first.nameRaw).normalized,
                        credit = 0.0,
                        debit = pair.second,
                        currency = pair.first.unit,
                        net = pair.second,
                        status = "VALID",
                        confidenceScore = 90,
                        sourceCoordinates = "pdf:$index",
                        issues = null,
                        approved = 1
                    )
                }
            }

            if (rowsToSave.isEmpty()) {
                error = "لم يتم العثور على سجلات قابلة للاستيراد داخل PDF."
                repo.db.importDao().updateSession(
                    repo.db.importDao().sessionById(sid)?.copy(
                        status = SessionStatus.FAILED.name
                    ) ?: return@launch
                )
                return@launch
            }

            try {
                withContext(Dispatchers.IO) {
                    repo.db.importDao().insertRows(rowsToSave)
                    repo.db.importDao().updateSession(
                        ImportSession(
                            id = sid,
                            fileName = name,
                            fileHash = hash,
                            fileType = "pdf",
                            kind = kind.name,
                            totalFound = rowsToSave.size,
                            validCount = rowsToSave.size,
                            reviewCount = 0,
                            ignoredCount = result.ignored,
                            totalCredit = result.totalCredit,
                            totalDebit = result.totalDebit,
                            net = result.totalDebit - result.totalCredit,
                            status = SessionStatus.READY_TO_IMPORT.name
                        )
                    )
                    ImportEngine.apply(repo, sid, kind, rowsToSave)
                }

                progress = "تم الاستيراد تلقائياً: ${rowsToSave.size} سجل"
                nav.navigate(Routes.HOME) {
                    popUpTo(Routes.IMPORT) { inclusive = true }
                }
            } catch (t: Throwable) {
                error = t.message ?: "فشل اعتماد البيانات المستخرجة."
                withContext(Dispatchers.IO) {
                    repo.db.importDao().sessionById(sid)?.let {
                        repo.db.importDao().updateSession(
                            it.copy(status = SessionStatus.FAILED.name)
                        )
                    }
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) startImport(uri, selectedKind, force = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("استيراد PDF", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "رجوع",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.NavyDark
                )
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = AppColors.DeepBlue
                    )
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Rounded.PictureAsPdf,
                                contentDescription = null,
                                tint = AppColors.CyanAccent,
                                modifier = Modifier.size(34.dp)
                            )
                            Column {
                                Text(
                                    "محرك الاستيراد يعمل على PDF حالياً",
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "اختر ملف PDF، وسيستخرج المحرك البيانات ويستوردها تلقائياً. يدعم النص الأصلي وOCR العربي للملفات الممسوحة.",
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { showKindDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.PrimaryBlue
                    )
                ) {
                    Icon(Icons.Rounded.FileOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("اختيار ملف PDF")
                }
            }

            item {
                progress?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = AppColors.DeepBlue
                        )
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                color = AppColors.CyanAccent,
                                strokeWidth = 2.dp
                            )
                            Text(it, color = Color.White)
                        }
                    }
                }

                error?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = AppColors.RedDanger.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "تعذر استيراد PDF",
                                color = AppColors.RedDanger
                            )
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Gray
                            )
                            TextButton(onClick = { error = null }) {
                                Text("إغلاق")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showKindDialog) {
        AlertDialog(
            onDismissRequest = { showKindDialog = false },
            title = { Text("ماذا يوجد داخل PDF؟") },
            text = { Text("اختر نوع البيانات حتى يفسر المحرك الأعمدة بالشكل الصحيح.") },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            selectedKind = ImportKind.CUSTOMER
                            showKindDialog = false
                            picker.launch(arrayOf("application/pdf"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.PrimaryBlue
                        )
                    ) {
                        Text("العملاء والأرصدة", color = Color.White)
                    }

                    Button(
                        onClick = {
                            selectedKind = ImportKind.PRODUCT
                            showKindDialog = false
                            picker.launch(arrayOf("application/pdf"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.CyanAccent
                        )
                    ) {
                        Text("الأصناف والمخزون", color = Color.White)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showKindDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }

    dupSession?.let { dup ->
        AlertDialog(
            onDismissRequest = { dupSession = null },
            title = { Text("ملف PDF مستورد سابقاً") },
            text = {
                Text(
                    "هذا الملف تم تحليله من قبل. يمكنك حذف جلسة الاستيراد السابقة وإعادة تحليل PDF."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uri = pendingUri
                        dupSession = null
                        if (uri != null) {
                            startImport(uri, selectedKind, force = true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.RedDanger
                    )
                ) {
                    Text("إعادة تحليل PDF", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { dupSession = null }) {
                    Text("إلغاء")
                }
            }
        )
    }
}
