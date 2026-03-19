package com.ipcam.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ipcam.app.data.model.Camera
import com.ipcam.app.data.repository.CameraRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val cameras: List<Camera> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val registerError: String? = null,
    val registerSuccess: String? = null
)

class DashboardViewModel(private val cameraRepository: CameraRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState

    // Do NOT fetch in init — the ViewModel is created in MainActivity.onCreate() before
    // login, so the token is null at that point. Fetching is triggered by the Dashboard
    // composable via LaunchedEffect, which only runs after navigation (post-login).
    fun fetchCameras() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val cameras = cameraRepository.getCameras()
                _uiState.value = _uiState.value.copy(cameras = cameras, isLoading = false)
                Log.d(TAG, "Fetched ${cameras.size} cameras")
            } catch (e: Exception) {
                Log.e(TAG, "fetchCameras failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to fetch cameras"
                )
            }
        }
    }

    fun registerCamera(
        deviceId: String,
        password: String,
        name: String,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(registerError = null, registerSuccess = null)
            try {
                cameraRepository.registerCamera(deviceId, password, name)
                _uiState.value = _uiState.value.copy(registerSuccess = "Camera registered successfully!")
                fetchCameras()
                onSuccess()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    registerError = e.message ?: "Failed to register camera"
                )
            }
        }
    }

    fun clearRegisterMessages() {
        _uiState.value = _uiState.value.copy(registerError = null, registerSuccess = null)
    }

    class Factory(private val repository: CameraRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(repository) as T
        }
    }

    companion object {
        private const val TAG = "DashboardViewModel"
    }
}
