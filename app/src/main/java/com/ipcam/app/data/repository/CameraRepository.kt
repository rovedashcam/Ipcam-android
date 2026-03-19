package com.ipcam.app.data.repository

import com.ipcam.app.data.api.CameraApiService
import com.ipcam.app.data.model.Camera
import com.ipcam.app.data.model.RegisterCameraRequest

class CameraRepository(private val cameraApiService: CameraApiService) {

    suspend fun getCameras(): List<Camera> {
        return cameraApiService.getCameras().data
    }

    suspend fun registerCamera(deviceId: String, password: String, name: String) {
        cameraApiService.registerCamera(RegisterCameraRequest(deviceId, password, name))
    }
}
