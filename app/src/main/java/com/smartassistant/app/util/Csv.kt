package com.smartassistant.app.util

object Csv {
    fun parse(text: String): List<List<String>> {
        val clean = text.replace("\uFEFF", "")
        val delim = if (clean.take(500).count { it == ';' } > clean.take(500).count { it == ',' }) ';' else ','
        val rows = mutableListOf<List<String>>()
        val cur = mutableListOf<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < clean.length) {
            val ch = clean[i]
            when {
                inQ -> when (ch) {
                    '"' -> if (i + 1 < clean.length && clean[i + 1] == '"') { sb.append('"'); i++ } else inQ = false
                    else -> sb.append(ch)
                }
                ch == '"' -> inQ = true
                ch == delim -> { cur.add(sb.toString()); sb.clear() }
                ch == '\n' -> { cur.add(sb.toString()); sb.clear(); rows.add(cur.toList()); cur.clear() }
                ch == '\r' -> {}
                else -> sb.append(ch)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur.toList()) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }

    fun encode(rows: List<List<String>>): String = buildString {
        rows.forEach { r ->
            appendLine(r.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
        }
    }
}
