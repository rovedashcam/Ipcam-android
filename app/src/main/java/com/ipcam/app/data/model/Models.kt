package com.ipcam.app.data.model

data class LoginRequest(
    val email: String,
    val password: String
)

data class SignupRequest(
    val email: String,
    val password: String
)

data class AuthResponse(
    val token: String
)

data class User(
    val id: String,
    val email: String
)

data class Camera(
    val deviceId: String,
    val name: String,
    val status: String,
    val lastSeen: String? = null
)

data class CamerasResponse(
    val data: List<Camera>
)

data class RegisterCameraRequest(
    val deviceId: String,
    val password: String,
    val name: String
)
