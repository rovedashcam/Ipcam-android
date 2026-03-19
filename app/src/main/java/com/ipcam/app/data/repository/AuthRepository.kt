package com.ipcam.app.data.repository

import com.auth0.jwt.JWT
import com.ipcam.app.data.api.AuthApiService
import com.ipcam.app.data.local.TokenStorage
import com.ipcam.app.data.model.LoginRequest
import com.ipcam.app.data.model.SignupRequest
import com.ipcam.app.data.model.User

class AuthRepository(
    private val authApiService: AuthApiService,
    private val tokenStorage: TokenStorage
) {

    suspend fun login(email: String, password: String): User {
        val response = authApiService.login(LoginRequest(email, password))
        tokenStorage.saveToken(response.token)
        return decodeToken(response.token)
    }

    suspend fun signup(email: String, password: String): User {
        val response = authApiService.signup(SignupRequest(email, password))
        tokenStorage.saveToken(response.token)
        return decodeToken(response.token)
    }

    fun logout() {
        tokenStorage.clearToken()
    }

    fun restoreSession(): User? {
        val token = tokenStorage.getToken() ?: return null
        return try {
            decodeToken(token)
        } catch (e: Exception) {
            tokenStorage.clearToken()
            null
        }
    }

    private fun decodeToken(token: String): User {
        val decoded = JWT.decode(token)
        val id = decoded.getClaim("id").asString() ?: ""
        val email = decoded.getClaim("email").asString() ?: ""
        return User(id = id, email = email)
    }
}
