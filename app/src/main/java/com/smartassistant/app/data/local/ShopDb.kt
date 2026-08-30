package com.smartassistant.app.data.local

import android.content.Context
import androidx.room.*
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

@Database(entities = [ShopSettings::class], version = 1, exportSchema = false)
abstract class ShopDatabase : RoomDatabase() {
    abstract fun shopDao(): ShopDao
    companion object {
        @Volatile private var i: ShopDatabase? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c, ShopDatabase::class.java, "smart_assistant.db")
                .fallbackToDestructiveMigration().build().also { i = it }
        }
    }
}
