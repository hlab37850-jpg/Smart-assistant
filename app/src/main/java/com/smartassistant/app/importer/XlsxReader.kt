package com.smartassistant.app.importer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class Sheet(val name: String, val rows: List<List<String>>)

object XlsxReader {
    fun read(file: File): List<Sheet> {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")
                ?.let { parseStrings(zip.getInputStream(it)) } ?: emptyList()
            val names = zip.getEntry("xl/workbook.xml")
                ?.let { parseSheetNames(zip.getInputStream(it)) } ?: listOf("Sheet1")
            val out = mutableListOf<Sheet>()
            var idx = 1
            for (n in names) {
                val e = zip.getEntry("xl/worksheets/sheet$idx.xml")
                if (e != null) out += Sheet(n, parseRows(zip.getInputStream(e), shared))
                idx++
            }
            return out
        }
    }

    private fun parser(inp: InputStream): XmlPullParser {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(inp, "UTF-8"); return p
    }

    private fun parseStrings(inp: InputStream): List<String> {
        val p = parser(inp); val out = mutableListOf<String>(); val sb = StringBuilder(); var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "t") { inT = true; sb.clear() }
                XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (p.name == "t") inT = false
                    else if (p.name == "si") { out += sb.toString(); sb.clear() }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheetNames(inp: InputStream): List<String> {
        val p = parser(inp); val out = mutableListOf<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "sheet")
                out += p.getAttributeValue(null, "name") ?: "Sheet"
            ev = p.next()
        }
        return out
    }

    private fun parseRows(inp: InputStream, shared: List<String>): List<List<String>> {
        val p = parser(inp); val rows = mutableListOf<List<String>>()
        var cur: MutableList<Pair<Int, String>>? = null
        var t = ""; var ref = ""; val vb = StringBuilder(); var inV = false; var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = mutableListOf()
                    "c" -> { t = p.getAttributeValue(null, "t") ?: ""; ref = p.getAttributeValue(null, "r") ?: "" }
                    "v" -> { inV = true; vb.clear() }
                    "t" -> { inT = true; vb.clear() }
                }
                XmlPullParser.TEXT -> if (inV || inT) vb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val c = cur
                        if (c != null) {
                            val raw = vb.toString()
                            val value = when (t) {
                                "s" -> shared.getOrElse(raw.toIntOrNull() ?: 0) { "" }
                                "inlineStr" -> raw
                                else -> raw
                            }
                            val col = colIndex(ref)
                            c += col to value
                        }
                        vb.clear()
                    }
                    "row" -> {
                        val c = cur
                        if (c != null && c.isNotEmpty()) {
                            val max = c.maxOf { it.first }
                            val arr = MutableList(max + 1) { "" }
                            c.forEach { (i, v) -> if (i >= 0 && i <= max) arr[i] = v }
                            rows += arr
                        }
                        cur = null
                    }
                }
            }
            ev = p.next()
        }
        return rows
    }

    private fun colIndex(ref: String): Int =
        ref.takeWhile { it.isLetter() }.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
}
