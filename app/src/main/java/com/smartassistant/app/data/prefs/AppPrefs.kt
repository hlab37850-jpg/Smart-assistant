package com.smartassistant.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore(name = "smart_assistant_prefs")

class AppPrefs(private val ctx: Context) {
    private val ONB = booleanPreferencesKey("onboarding_done")
    private val SHOP = booleanPreferencesKey("shop_setup_done")
    private val THEME = stringPreferencesKey("theme")

    val onboardingDone: Flow<Boolean> = ctx.ds.data.map { it[ONB] ?: false }
    val shopSetupDone: Flow<Boolean> = ctx.ds.data.map { it[SHOP] ?: false }
    val themeMode: Flow<String> = ctx.ds.data.map { it[THEME] ?: "SYSTEM" }

    suspend fun setOnboardingDone() = ctx.ds.edit { it[ONB] = true }
    suspend fun setShopSetupDone() = ctx.ds.edit { it[SHOP] = true }
    suspend fun setThemeMode(m: String) = ctx.ds.edit { it[THEME] = m }

    companion object {
        @Volatile private var i: AppPrefs? = null
        fun get(c: Context) = i ?: synchronized(this) { i ?: AppPrefs(c.applicationContext).also { i = it } }
    }
}
