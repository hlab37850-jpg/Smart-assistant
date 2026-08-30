package com.smartassistant.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Export {
    fun csv(ctx: Context, fileName: String, content: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv) ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            true
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, fileName).writeText(content)
            true
        }
    }.getOrDefault(false)
}
