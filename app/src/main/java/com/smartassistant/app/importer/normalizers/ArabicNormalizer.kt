package com.smartassistant.app.importer.normalizers

import java.text.Normalizer

/**
 * ثلاث طبقات للنص العربي:
 * - raw: كما هو تماماً (Zero Data Loss)
 * - display: بعد تصحيح الاتجاه والمسافات للعرض
 * - normalized: للمقارنة والبحث (توحيد الألف، إزالة التطويل، lowercase)
 */
object ArabicNormalizer {

    data class Triple(val raw: String, val display: String, val normalized: String)

    private val TATWEEL = Regex("[\\u0640\\u200B\\uFEFF\\u200E\\u200F]")
    private val DIACRITICS = Regex("[\\u064B-\\u0652\\u0670]")

    fun process(input: String?): Triple {
        val raw = input?.trim() ?: ""
        if (raw.isEmpty()) return Triple("", "", "")
        val display = raw.replace(TATWEEL, "").replace(Regex("\\s+"), " ").trim()
        var n = display
        n = n.replace(DIACRITICS, "")
        // توحيد الألف
        n = n.replace(Regex("[أإآٱ]"), "ا").replace("ى","ي").replace("ؤ","و").replace("ئ","ي")
        n = n.replace("ة","ه")
        n = Normalizer.normalize(n, Normalizer.Form.NFKD)
        n = n.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        n = n.replace(Regex("\\s+"), " ").trim().lowercase()
        return Triple(raw, display, n)
    }
}
