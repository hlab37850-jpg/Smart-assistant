package com.smartassistant.app.backup

import android.content.Context
import com.smartassistant.app.data.local.AppDatabase
import com.smartassistant.app.data.local.entity.Backup
import java.io.File

object BackupManager {
    /**
     * Creates a consistent SQLite snapshot without closing/resetting the live Room database.
     * Closing Room while Compose/Flow observers are active can race with DAO transactions and
     * cause SQLITE_BUSY on the next write. VACUUM INTO produces a standalone consistent copy.
     */
    fun create(ctx: Context, auto: Boolean): Backup? {
        val db = AppDatabase.get(ctx)
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val dst = File(dir, "backup_${System.currentTimeMillis()}.db")
        val escaped = dst.absolutePath.replace("'", "''")

        val ok = runCatching {
            var last: Throwable? = null
            repeat(4) { attempt ->
                try {
                    db.openHelper.writableDatabase.execSQL("VACUUM INTO '$escaped'")
                    last = null
                    return@repeat
                } catch (t: Throwable) {
                    last = t
                    if (attempt < 3) Thread.sleep((250L * (attempt + 1)))
                }
            }
            if (last != null) throw last!!
            dst.exists() && dst.length() > 0L
        }.getOrDefault(false)

        if (!ok) {
            dst.delete()
            return null
        }

        return Backup(
            filePath = dst.absolutePath,
            size = dst.length(),
            auto = if (auto) 1 else 0
        )
    }

    fun restore(ctx: Context, file: File): Boolean {
        if (!file.exists()) return false
        val db = AppDatabase.get(ctx)
        runCatching { db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)") }
        db.close()
        file.copyTo(ctx.getDatabasePath("smart_assistant.db"), overwrite = true)
        AppDatabase.reset()
        return true
    }
}
