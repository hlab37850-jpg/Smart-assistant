package com.smartassistant.app.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Fmt {
    private val nf: NumberFormat = NumberFormat.getInstance(Locale.US).apply {
        minimumFractionDigits = 0; maximumFractionDigits = 2
    }
    fun money(v: Double): String = nf.format(v)
    fun today(): String = LocalDate.now().toString()
    fun plusDays(n: Long): String = LocalDate.now().plusDays(n).toString()
    fun daysSince(iso: String): Long =
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(iso), LocalDate.now()) }.getOrDefault(0)
    fun nowTime(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
    fun ts(ts: Long): String = DateTimeFormatter.ofPattern("MM/dd HH:mm").format(
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), java.time.ZoneId.systemDefault()))
}
