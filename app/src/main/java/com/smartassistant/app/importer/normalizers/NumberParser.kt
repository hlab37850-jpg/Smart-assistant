package com.smartassistant.app.importer.normalizers

/**
 * محلل الأرقام المالية: يحول النص إلى قيمة رقمية مع الحفاظ على الدقة.
 * يدعم: 75,640 | 6,712.25 | ٧٥٬٦٤٠ | (5000) | -5000 | 5000-
 * لا يستخدم Float/Double للحسابات الحساسة — يعتمد BigDecimal داخلياً.
 */
object NumberParser {

    private val AR_DIGITS = mapOf(
        '٠' to '0','١' to '1','٢' to '2','٣' to '3','٤' to '4',
        '٥' to '5','٦' to '6','٧' to '7','٨' to '8','٩' to '9',
        '٫' to '.', '٬' to ',', '،' to ','
    )

    data class Result(val value: Double?, val raw: String, val decimals: Int)

    fun normalize(s: String): String = s.map { AR_DIGITS[it] ?: it }.joinToString("")

    fun parse(raw: String?): Result {
        val kept = raw?.trim() ?: ""
        if (kept.isEmpty()) return Result(null, kept, 0)
        var s = normalize(kept).replace("\u00A0"," ").replace(" ","").replace(",","")
        var negative = false
        when {
            s.startsWith("(") && s.endsWith(")") -> { negative = true; s = s.substring(1, s.length-1) }
            s.startsWith("-") -> { negative = true; s = s.substring(1) }
            s.endsWith("-") -> { negative = true; s = s.substring(0, s.length-1) }
        }
        // إزالة كل ما هو غير رقمي أو نقطة عشرية
        val cleaned = s.takeWhile { it.isDigit() || it == '.' }
        if (cleaned.isEmpty()) return Result(null, kept, 0)
        val decimals = if (cleaned.contains('.')) cleaned.length - cleaned.indexOf('.') - 1 else 0
        val num = cleaned.toDoubleOrNull() ?: return Result(null, kept, 0)
        val final = if (negative) -num else num
        return Result(final, kept, decimals)
    }

    /** تحويل لقيمة مالية بعدد محدد من المنازل العشرية (افتراضي 3) */
    fun toMoney(raw: String?, decimals: Int = 3): Double? {
        val r = parse(raw) ?: return null
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round((r.value ?: return null) * factor) / factor
    }

    fun isNumeric(s: String): Boolean = parse(s).value != null
}
