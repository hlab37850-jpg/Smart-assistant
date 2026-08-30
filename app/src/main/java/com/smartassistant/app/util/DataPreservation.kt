package com.smartassistant.app.util

object DataPreservation {
    fun keepRaw(s: String?): String = s?.trim()?.replace(Regex("[\\u200B\\uFEFF]"), "") ?: ""

    private val AR = mapOf('٠' to '0','١' to '1','٢' to '2','٣' to '3','٤' to '4',
        '٥' to '5','٦' to '6','٧' to '7','٨' to '8','٩' to '9','٫' to '.','٬' to ',','،' to ',')

    fun normalizeDigits(s: String): String = s.map { AR[it] ?: it }.joinToString("")

    fun parseAmount(raw: String?): ParsedAmount {
        val kept = keepRaw(raw)
        if (kept.isEmpty()) return ParsedAmount(null, kept)
        var s = normalizeDigits(kept).replace(",", "").replace(" ", "")
        var neg = false
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.removePrefix("(").removeSuffix(")") }
        if (s.startsWith("-")) { neg = !neg; s = s.removePrefix("-") }
        if (s.endsWith("-")) { neg = !neg; s = s.removeSuffix("-") }
        val num = s.takeWhile { it.isDigit() || it == '.' }.toDoubleOrNull()
        return ParsedAmount(if (num == null) null else (if (neg) -num else num), kept)
    }

    fun parsePhone(raw: String?): String? {
        val d = normalizeDigits(keepRaw(raw)).filter { it.isDigit() }
        return d.ifEmpty { null }
    }

    fun normalizeForMatch(s: String): String =
        normalizeDigits(s).lowercase().replace(Regex("[\\s\\-_.×x*()+/]"), " ").trim()
}
data class ParsedAmount(val value: Double?, val raw: String)
