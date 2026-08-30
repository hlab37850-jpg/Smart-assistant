package com.smartassistant.app.backup

import android.content.Context
import com.smartassistant.app.data.local.AppDatabase
import com.smartassistant.app.data.local.entity.Backup
import java.io.File

object BackupManager {
    fun create(ctx: Context, auto: Boolean): Backup? {
        AppDatabase.get(ctx).openHelper.close()
        val src = ctx.getDatabasePath("smart_assistant.db")
        if (!src.exists()) { AppDatabase.reset(); return null }
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val dst = File(dir, "backup_${System.currentTimeMillis()}.db")
        src.copyTo(dst, overwrite = true)
        AppDatabase.reset()
        return Backup(filePath = dst.absolutePath, size = dst.length(), auto = if (auto) 1 else 0)
    }

    fun restore(ctx: Context, file: File): Boolean {
        if (!file.exists()) return false
        AppDatabase.get(ctx).openHelper.close()
        file.copyTo(ctx.getDatabasePath("smart_assistant.db"), overwrite = true)
        AppDatabase.reset()
        return true
    }
}
