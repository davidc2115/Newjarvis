package com.ai.dapp.developer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ai.dapp.developer.ui.screens.*

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object AIModels : Screen("ai_models")
    object GitHub : Screen("github")
    object Terminal : Screen("terminal")
    object DappTemplates : Screen("dapp_templates")
    object DappEditor : Screen("dapp_editor/{templateId}") {
        fun createRoute(templateId: String) = "dapp_editor/$templateId"
    }
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController = androidx.navigation.compose.rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.AIModels.route) {
            AIModelsScreen(navController = navController)
        }
        composable(Screen.GitHub.route) {
            GitHubScreen(navController = navController)
        }
        composable(Screen.Terminal.route) {
            TerminalScreen(navController = navController)
        }
        composable(Screen.DappTemplates.route) {
            DappTemplatesScreen(navController = navController)
        }
        composable(Screen.DappEditor.route) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId") ?: ""
            DappEditorScreen(
                templateId = templateId,
                navController = navController
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
    }
}
