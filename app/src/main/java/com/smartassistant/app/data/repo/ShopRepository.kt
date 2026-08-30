package com.smartassistant.app.data.repo

import android.content.Context
import com.smartassistant.app.data.local.ShopDatabase
import com.smartassistant.app.data.local.ShopSettings
import com.smartassistant.app.data.prefs.AppPrefs

class ShopRepository(ctx: Context) {
    private val db = ShopDatabase.get(ctx)
    private val prefs = AppPrefs.get(ctx)
    fun observe() = db.shopDao().observe()
    suspend fun current(): ShopSettings? = runCatching { db.shopDao().get() }.getOrNull()
    suspend fun save(s: ShopSettings) {
        db.shopDao().save(s)
        prefs.setShopSetupDone()
    }
}
