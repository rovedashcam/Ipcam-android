package com.ipcam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ipcam.app.ui.navigation.AppNavigation
import com.ipcam.app.ui.theme.IpCamTheme
import com.ipcam.app.ui.viewmodel.AuthViewModel
import com.ipcam.app.ui.viewmodel.CameraViewModel
import com.ipcam.app.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as IpCamApplication

        // ViewModels are scoped to the Activity — they survive screen rotations and
        // are shared across all navigation destinations, matching Zustand's global store behaviour.
        val authViewModel: AuthViewModel by viewModels {
            AuthViewModel.Factory(app.authRepository)
        }
        val dashboardViewModel: DashboardViewModel by viewModels {
            DashboardViewModel.Factory(app.cameraRepository)
        }
        // CameraViewModel is AndroidViewModel — factory provided automatically
        val cameraViewModel: CameraViewModel by viewModels()

        setContent {
            IpCamTheme {
                AppNavigation(
                    authViewModel = authViewModel,
                    dashboardViewModel = dashboardViewModel,
                    cameraViewModel = cameraViewModel
                )
            }
        }
    }
}
