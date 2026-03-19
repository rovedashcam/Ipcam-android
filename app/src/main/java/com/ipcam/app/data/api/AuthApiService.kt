package com.ipcam.app.data.api

import com.ipcam.app.data.model.AuthResponse
import com.ipcam.app.data.model.LoginRequest
import com.ipcam.app.data.model.SignupRequest
import retrofit2.http.Body
import retrofit2.http.POST

interface AuthApiService {

    @POST("user/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("user/signup")
    suspend fun signup(@Body request: SignupRequest): AuthResponse
}
