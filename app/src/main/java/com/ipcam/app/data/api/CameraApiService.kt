package com.ipcam.app.data.api

import com.ipcam.app.data.model.CamerasResponse
import com.ipcam.app.data.model.RegisterCameraRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface CameraApiService {

    @GET("camera/cameras")
    suspend fun getCameras(): CamerasResponse

    @POST("camera/register")
    suspend fun registerCamera(@Body request: RegisterCameraRequest): Any
}
