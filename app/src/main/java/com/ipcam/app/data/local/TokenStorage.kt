package com.ipcam.app.data.local

import android.content.Context

/**
 * Synchronous token storage backed by SharedPreferences.
 * Analogous to localStorage in the web app.
 * For production, consider EncryptedSharedPreferences for security.
 */
class TokenStorage(context: Context) {

    private val prefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    companion object {
        private const val KEY_TOKEN = "token"
    }
}
