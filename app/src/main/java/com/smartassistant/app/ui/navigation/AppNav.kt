package com.smartassistant.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.smartassistant.app.ui.components.MainScaffold
import com.smartassistant.app.ui.screens.Onboarding
import com.smartassistant.app.ui.screens.ShopSetup

object Routes {
    const val ONBOARDING = "onboarding"
    const val SHOP_SETUP = "shop_setup"
    const val MAIN = "main"
}

@Composable
fun AppNav(nav: NavHostController, start: String) {
    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            Onboarding(
                onStart = { nav.navigate(Routes.SHOP_SETUP) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                onSkip = { nav.navigate(Routes.SHOP_SETUP) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.SHOP_SETUP) {
            ShopSetup(onDone = { nav.navigate(Routes.MAIN) { popUpTo(Routes.SHOP_SETUP) { inclusive = true } } })
        }
        composable(Routes.MAIN) { MainScaffold() }
    }
}
