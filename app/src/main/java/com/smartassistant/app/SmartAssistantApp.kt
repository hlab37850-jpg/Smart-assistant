package com.smartassistant.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.smartassistant.app.notifications.ReminderScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class SmartAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // إصلاح انهيار GlyphList not found — تهيئة موارد PDFBox مرة واحدة
        runCatching { PDFBoxResourceLoader.init(this) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("dues", "الاستحقاقات", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("stock", "المخزون", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel("system", "النظام", NotificationManager.IMPORTANCE_LOW))
        }
        ReminderScheduler.scheduleDailyScan(this)
    }
}
