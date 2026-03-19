package com.ipcam.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ipcam.app.ui.screens.CameraViewerScreen
import com.ipcam.app.ui.screens.DashboardScreen
import com.ipcam.app.ui.screens.LoginScreen
import com.ipcam.app.ui.screens.SignupScreen
import com.ipcam.app.ui.viewmodel.AuthViewModel
import com.ipcam.app.ui.viewmodel.CameraViewModel
import com.ipcam.app.ui.viewmodel.DashboardViewModel

private object Routes {
    const val LOGIN = "login"
    const val SIGNUP = "signup"
    const val DASHBOARD = "dashboard"
    const val CAMERA_VIEWER = "camera/{cameraId}"
    fun cameraViewer(cameraId: String) = "camera/$cameraId"
}

@Composable
fun AppNavigation(
    authViewModel: AuthViewModel,
    dashboardViewModel: DashboardViewModel,
    cameraViewModel: CameraViewModel
) {
    val navController = rememberNavController()
    val authState by authViewModel.uiState.collectAsStateWithLifecycle()

    // Start at dashboard if already authenticated — mirrors the ProtectedRoute/PublicRoute logic
    val startDestination = if (authState.isAuthenticated) Routes.DASHBOARD else Routes.LOGIN

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = authViewModel,
                onNavigateToSignup = { navController.navigate(Routes.SIGNUP) },
                onLoginSuccess = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.SIGNUP) {
            SignupScreen(
                viewModel = authViewModel,
                onNavigateToLogin = { navController.popBackStack() },
                onSignupSuccess = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SIGNUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                authViewModel = authViewModel,
                dashboardViewModel = dashboardViewModel,
                onNavigateToCamera = { cameraId ->
                    navController.navigate(Routes.cameraViewer(cameraId))
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CAMERA_VIEWER,
            arguments = listOf(navArgument("cameraId") { type = NavType.StringType })
        ) { backStackEntry ->
            val cameraId = backStackEntry.arguments?.getString("cameraId") ?: return@composable
            CameraViewerScreen(
                cameraId = cameraId,
                authViewModel = authViewModel,
                cameraViewModel = cameraViewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
