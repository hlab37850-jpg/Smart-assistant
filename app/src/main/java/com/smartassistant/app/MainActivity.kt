package com.smartassistant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.smartassistant.app.data.prefs.AppPrefs
import com.smartassistant.app.data.repo.ShopRepository
import com.smartassistant.app.ui.components.AppShell
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.screens.Splash
import com.smartassistant.app.ui.theme.SmartAssistantTheme
import com.smartassistant.app.ui.theme.ThemeMode
import com.smartassistant.app.util.CrashHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val startRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        CrashHandler.install(this)

        val prefs = AppPrefs.get(this)
        val deepLink = intent?.getStringExtra("route")

        splash.setKeepOnScreenCondition { startRoute.value == null }
        lifecycleScope.launch {
            val repo = ShopRepository(this@MainActivity)
            val onb = prefs.onboardingDone.first()
            val hasShop = repo.current() != null
            startRoute.value = when {
                !onb -> Routes.ONBOARDING
                !hasShop -> Routes.SHOP_SETUP
                else -> Routes.HOME
            }
        }

        setContent {
            val theme by prefs.themeMode.collectAsState(initial = "SYSTEM")
            val mode = when (theme) {
                "LIGHT" -> ThemeMode.LIGHT
                "DARK" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            SmartAssistantTheme(mode) {
                Surface(Modifier.fillMaxSize()) {
                    var showSplash by remember { mutableStateOf(true) }
                    val route = startRoute.value
                    if (route == null || showSplash) {
                        Splash(onFinish = { showSplash = false })
                    } else {
                        AppShell(route, deepLink)
                    }
                }
            }
        }
    }
}
