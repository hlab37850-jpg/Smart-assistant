package com.smartassistant.app.data.local

import android.content.Context
import androidx.room.*
import com.smartassistant.app.data.local.dao.*
import com.smartassistant.app.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shop_settings")
data class ShopSettings(
    @PrimaryKey val id: Int = 1,
    val name: String = "",
    val phone: String = "",
    val whatsapp: String = "",
    val address: String = "",
    val logoPath: String? = null,
    val currency: String = "",
)

@Dao
interface ShopDao {
    @Query("SELECT * FROM shop_settings WHERE id = 1") fun observe(): Flow<ShopSettings?>
    @Query("SELECT * FROM shop_settings WHERE id = 1") suspend fun get(): ShopSettings?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(s: ShopSettings)
}

@Database(entities = [
    ShopSettings::class, Customer::class, CustomerNote::class, DueDate::class,
    AppNotification::class, Category::class, Product::class, Inventory::class,
    ImportSession::class, ImportRecord::class, ImportError::class, Backup::class,
    ActivityLog::class, AIConversation::class, AIMessage::class,
], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shopDao(): ShopDao
    abstract fun customerDao(): CustomerDao
    abstract fun productDao(): ProductDao
    abstract fun dueDao(): DueDao
    abstract fun notificationDao(): NotificationDao
    abstract fun categoryDao(): CategoryDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun noteDao(): NoteDao
    abstract fun importDao(): ImportDao
    abstract fun backupDao(): BackupDao
    abstract fun logDao(): LogDao
    abstract fun aiDao(): AIDao

    companion object {
        @Volatile private var i: AppDatabase? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c, AppDatabase::class.java, "smart_assistant.db")
                .fallbackToDestructiveMigration().build().also { i = it }
        }
    }
}
