package com.smartassistant.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.smartassistant.app.data.prefs.AppPrefs
import com.smartassistant.app.data.repo.ShopRepository
import com.smartassistant.app.ui.navigation.AppNav
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

        splash.setKeepOnScreenCondition { startRoute.value == null }
        lifecycleScope.launch {
            val prefs = AppPrefs.get(this@MainActivity)
            val repo = ShopRepository(this@MainActivity)
            val onb = prefs.onboardingDone.first()
            val hasShop = repo.current() != null
            startRoute.value = when {
                !onb -> Routes.ONBOARDING
                !hasShop -> Routes.SHOP_SETUP
                else -> Routes.DASHBOARD
            }
        }

        setContent {
            SmartAssistantTheme(ThemeMode.SYSTEM) {
                Surface(Modifier.fillMaxSize()) {
                    var showSplash by remember { mutableStateOf(true) }
                    val route = startRoute.value
                    if (route == null || showSplash) {
                        Splash(onFinish = { showSplash = false })
                    } else {
                        val nav = rememberNavController()
                        AppNav(nav, route)
                    }
                }
            }
        }
    }
}
