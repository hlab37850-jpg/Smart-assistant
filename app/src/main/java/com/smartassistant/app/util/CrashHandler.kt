package com.smartassistant.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

object CrashHandler {
    fun install(ctx: Context) {
        val def = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(ctx, e) } catch (_: Exception) {}
            def?.uncaughtException(t, e) ?: kotlin.system.exitProcess(1)
        }
    }
    private fun write(ctx: Context, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val text = "Time: ${Date()}\nDevice: ${Build.MODEL} API ${Build.VERSION.SDK_INT}\n\n$sw"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "smart_assistant_crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            ctx.contentResolver.insert(MediaStore.Downloads.CONTENT_URI, cv)?.let {
                ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) }
            }
        }
    }
}
