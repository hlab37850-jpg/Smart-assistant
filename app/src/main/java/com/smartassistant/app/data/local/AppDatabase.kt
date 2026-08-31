package com.smartassistant.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.smartassistant.app.data.local.dao.*
import com.smartassistant.app.data.local.entity.*

@Database(entities = [
    ShopSettings::class, User::class, Customer::class, CustomerNote::class, DueDate::class,
    AppNotification::class, Category::class, Product::class, Inventory::class,
    ImportSession::class, ImportRawRow::class, ImportError::class, ImportProfile::class,
    Backup::class, ActivityLog::class, AIConversation::class, AIMessage::class,
], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shopDao(): ShopDao
    abstract fun userDao(): UserDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun dueDao(): DueDao
    abstract fun notificationDao(): NotificationDao
    abstract fun categoryDao(): CategoryDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun noteDao(): NoteDao
    abstract fun importDao(): ImportDao
    abstract fun profileDao(): ProfileDao
    abstract fun backupDao(): BackupDao
    abstract fun logDao(): LogDao
    abstract fun aiDao(): AIDao

    companion object {
        @Volatile private var i: AppDatabase? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c, AppDatabase::class.java, "smart_assistant.db")
                .fallbackToDestructiveMigration().build().also { i = it }
        }
        fun reset() { i = null }
    }
}
