package com.smartassistant.app.util

import com.smartassistant.app.data.local.entity.Customer

object Matching {
    fun phoneKey(phone: String?): String? {
        val d = DataPreservation.parsePhone(phone) ?: return null
        return if (d.length >= 9) d.takeLast(9) else d
    }
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return dp[b.length]
    }
    fun similarity(a: String, b: String): Double {
        val na = DataPreservation.normalizeForMatch(a)
        val nb = DataPreservation.normalizeForMatch(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        return 1.0 - levenshtein(na, nb).toDouble() / maxOf(na.length, nb.length)
    }
    fun confidence(existing: Customer, originalId: String?, phone: String?, code: String?, name: String): Double {
        if (originalId != null && existing.originalId == originalId) return 1.0
        val pk = phoneKey(phone); val ek = phoneKey(existing.phone)
        if (pk != null && pk == ek) return 0.9
        if (code != null && existing.code == code) return 0.85
        val sim = similarity(existing.name, name)
        return if (sim >= 0.8) sim * 0.8 else sim * 0.5
    }
}
