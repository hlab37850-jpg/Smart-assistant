package com.smartassistant.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartassistant.app.ui.navigation.Routes
import com.smartassistant.app.ui.screens.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(startRoute: String, deepLink: String? = null) {
    val nav = rememberNavController()
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route.orEmpty()
    val showBar = route in listOf(Routes.HOME, Routes.CUSTOMERS, Routes.PRODUCTS, Routes.REPORTS)

    LaunchedEffect(deepLink) {
        if (deepLink == "dues") nav.navigate(Routes.DUES)
        else if (deepLink != null && deepLink.startsWith("customer/")) {
            val id = deepLink.removePrefix("customer/").toLongOrNull() ?: 0L
            if (id > 0) nav.navigate(Routes.customerDetail(id))
        }
    }

    Scaffold(bottomBar = {
        if (showBar) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("الرئيسية") },
                    selected = route == Routes.HOME,
                    onClick = { nav.navigate(Routes.HOME) { popUpTo(Routes.HOME) { inclusive = true } } },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppColorsSafe.primary, selectedTextColor = AppColorsSafe.primary,
                        indicatorColor = AppColorsSafe.primary.copy(alpha = 0.1f))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.People, null) }, label = { Text("العملاء") },
                    selected = route == Routes.CUSTOMERS,
                    onClick = { nav.navigate(Routes.CUSTOMERS) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppColorsSafe.primary, selectedTextColor = AppColorsSafe.primary,
                        indicatorColor = AppColorsSafe.primary.copy(alpha = 0.1f))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.Inventory, null) }, label = { Text("الأصناف") },
                    selected = route == Routes.PRODUCTS,
                    onClick = { nav.navigate(Routes.PRODUCTS) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppColorsSafe.primary, selectedTextColor = AppColorsSafe.primary,
                        indicatorColor = AppColorsSafe.primary.copy(alpha = 0.1f))
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Rounded.Assessment, null) }, label = { Text("التقارير") },
                    selected = route == Routes.REPORTS,
                    onClick = { nav.navigate(Routes.REPORTS) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AppColorsSafe.primary, selectedTextColor = AppColorsSafe.primary,
                        indicatorColor = AppColorsSafe.primary.copy(alpha = 0.1f))
                )
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = startRoute, modifier = Modifier.padding(pad)) {
            composable(Routes.ONBOARDING) {
                Onboarding(
                    onStart = { nav.navigate(Routes.SHOP_SETUP) { popUpTo(Routes.ONBOARDING) { inclusive = true } } },
                    onSkip = { nav.navigate(Routes.SHOP_SETUP) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
            }
            composable(Routes.SHOP_SETUP) {
                ShopSetup(onDone = { nav.navigate(Routes.HOME) { popUpTo(Routes.SHOP_SETUP) { inclusive = true } } })
            }
            composable(Routes.HOME) { Dashboard(nav) }
            composable(Routes.CUSTOMERS) { CustomersScreen(nav) }
            composable(Routes.PRODUCTS) { ProductsScreen(nav) }
            composable(Routes.REPORTS) { ReportsScreen(nav) }
            composable(Routes.DUES) { DuesScreen(nav) }
            composable(Routes.NOTIFICATIONS) { NotificationsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.IMPORT) { ImportScreen(nav) }
            composable("review/{sid}") { e -> com.smartassistant.app.ui.screens.ReviewScreen(nav, e.arguments?.getLong("sid") ?: 0L) }
            composable(Routes.AI) { AiScreen(nav) }
            composable(Routes.ADD_CUSTOMER) { AddEditCustomerScreen(nav, null) }
            composable(Routes.EDIT_CUSTOMER, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                AddEditCustomerScreen(nav, e.arguments?.getLong("id"))
            }
            composable(Routes.CUSTOMER_DETAIL, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                CustomerDetailScreen(nav, e.arguments?.getLong("id") ?: 0L)
            }
            composable(Routes.ADD_PRODUCT) { AddEditProductScreen(nav, null) }
            composable(Routes.EDIT_PRODUCT, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                AddEditProductScreen(nav, e.arguments?.getLong("id"))
            }
            composable(Routes.PRODUCT_DETAIL, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ProductDetailScreen(nav, e.arguments?.getLong("id") ?: 0L)
            }
        }
    }
}

private object AppColorsSafe {
    val primary = com.smartassistant.app.ui.theme.AppColors.PrimaryBlue
}
