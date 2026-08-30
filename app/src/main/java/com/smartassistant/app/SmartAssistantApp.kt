package com.smartassistant.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class SmartAssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("dues", "الاستحقاقات", NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel("stock", "المخزون", NotificationManager.IMPORTANCE_DEFAULT))
            nm.createNotificationChannel(NotificationChannel("system", "النظام", NotificationManager.IMPORTANCE_LOW))
        }
    }
}
