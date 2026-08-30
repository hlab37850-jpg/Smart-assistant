package com.smartassistant.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.Assessment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smartassistant.app.ui.screens.CustomersScreen
import com.smartassistant.app.ui.screens.Dashboard
import com.smartassistant.app.ui.screens.ProductsScreen
import com.smartassistant.app.ui.screens.ReportsScreen
import com.smartassistant.app.ui.theme.AppColors

sealed class BottomRoute(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : BottomRoute("home", "الرئيسية", Icons.Rounded.Home)
    object Customers : BottomRoute("customers", "العملاء", Icons.Rounded.People)
    object Products : BottomRoute("products", "الأصناف", Icons.Rounded.Inventory)
    object Reports : BottomRoute("reports", "التقارير", Icons.Rounded.Assessment)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                val items = listOf(
                    BottomRoute.Home, BottomRoute.Customers,
                    BottomRoute.Products, BottomRoute.Reports
                )
                items.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        selected = currentRoute == item.route,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.PrimaryBlue,
                            selectedTextColor = AppColors.PrimaryBlue,
                            indicatorColor = AppColors.PrimaryBlue.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = BottomRoute.Home.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(BottomRoute.Home.route) { Dashboard(navController) }
            composable(BottomRoute.Customers.route) { CustomersScreen(navController) }
            composable(BottomRoute.Products.route) { ProductsScreen() }
            composable(BottomRoute.Reports.route) { ReportsScreen() }
        }
    }
}
