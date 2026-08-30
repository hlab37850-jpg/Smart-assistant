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
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = "Time: ${Date()}\nDevice: ${Build.MODEL} API ${Build.VERSION.SDK_INT}\n\n$sw"

        // 1) نسخة داخلية دائماً (لأغراض النظام)
        try { ctx.filesDir.resolve("crash_log.txt").writeText(text) } catch (_: Exception) {}

        // 2) نسخة في مجلد التنزيلات عبر MediaStore.Files (API عام ومستقر)
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "smart_assistant_crash.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                uri?.let {
                    ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) }
                    cv.clear()
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    ctx.contentResolver.update(it, cv, null, null)
                }
            } catch (_: Exception) {}
        }
    }
}
