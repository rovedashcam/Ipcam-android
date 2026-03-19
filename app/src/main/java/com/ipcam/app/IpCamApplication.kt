package com.ipcam.app

import android.app.Application
import com.ipcam.app.data.api.RetrofitClient
import com.ipcam.app.data.local.TokenStorage
import com.ipcam.app.data.repository.AuthRepository
import com.ipcam.app.data.repository.CameraRepository

class IpCamApplication : Application() {

    lateinit var tokenStorage: TokenStorage
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var cameraRepository: CameraRepository
        private set

    override fun onCreate() {
        super.onCreate()

        tokenStorage = TokenStorage(this)

        val (authApiService, cameraApiService) = RetrofitClient.create {
            tokenStorage.getToken()
        }

        authRepository = AuthRepository(authApiService, tokenStorage)
        cameraRepository = CameraRepository(cameraApiService)
    }
}
