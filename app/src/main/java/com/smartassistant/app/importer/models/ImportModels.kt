package com.smartassistant.app.importer.models

/**
 * كلمة مستخرجة من PDF مع إحداثياتها المكانية.
 * هذا هو أساس النظام: كل شيء يُبنى على الإحداثيات لا على ترتيب النص.
 */
data class WordBox(
    val text: String,
    val page: Int,
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float,
    val baseline: Float = y1
) {
    val centerX: Float get() = (x0 + x1) / 2f
    val centerY: Float get() = (y0 + y1) / 2f
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
}

enum class RowStatus {
    VALID, WARNING, NEEDS_REVIEW, DUPLICATE, INCOMPLETE,
    INVALID_NUMBER, MISSING_NAME, MISSING_CURRENCY,
    HEADER_OR_FOOTER, TOTAL_ROW, SKIPPED
}

enum class SessionStatus {
    UPLOADED, PROCESSING, EXTRACTED, VALIDATING, NEEDS_REVIEW,
    READY_TO_IMPORT, IMPORTING, COMPLETED, FAILED, CANCELLED, ROLLED_BACK
}

enum class FileType { PDF, CSV, XLSX, IMAGE, UNKNOWN }
enum class PdfType { NATIVE, SCANNED, HYBRID }
enum class ImportKind { CUSTOMER, PRODUCT }

/** عمود تم اكتشافه بالإحداثيات */
data class ColumnSpec(
    val key: String,           // name, credit, debit, currency, code
    val label: String,         // اسم العميل، لك، عليك، العملة
    val xStart: Float,
    val xEnd: Float,
    val rtlIndex: Int          // 0 = أقصى اليمين
)

/** صف مُعاد بناؤه */
data class ReconstructedRow(
    val page: Int,
    val rowNumber: Int,
    val centerY: Float,
    val cells: Map<String, String>,   // key -> text
    val sourceCoordinates: String,
    val rawSource: String
)

/** سجل جاهز للتحقق */
data class CustomerImportRow(
    val page: Int,
    val rowNumber: Int,
    val nameRaw: String,
    val nameDisplay: String,
    val nameNormalized: String,
    val credit: Double,
    val debit: Double,
    val currency: String?,
    val net: Double,
    val status: RowStatus,
    val issues: MutableList<String> = mutableListOf(),
    val confidenceScore: Int,
    val sourceCoordinates: String,
    val rawSource: String
)

/** ملف تعريف للتقرير */
data class FileProfile(
    val key: String,
    val name: String,
    val kind: ImportKind,
    val identificationKeywords: List<String>,
    val columnHints: List<String>,      // name, credit, debit, currency, code
    val headerKeywords: List<String>,
    val footerKeywords: List<String>,
    val totalKeywords: List<String>
)

/** نتيجة الاستخراج قبل التحقق */
data class ExtractionResult(
    val words: List<WordBox>,
    val detectedProfile: FileProfile?,
    val columns: List<ColumnSpec>,
    val rows: List<ReconstructedRow>,
    val skippedRows: List<ReconstructedRow>,
    val pdfType: PdfType
)
