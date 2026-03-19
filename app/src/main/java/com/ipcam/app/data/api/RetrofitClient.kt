package com.ipcam.app.data.api

import com.ipcam.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    /**
     * Creates Retrofit service instances.
     * @param getToken lambda to retrieve the current auth token synchronously — mirrors the
     *                 request interceptor pattern from the web app's api.ts.
     */
    fun create(getToken: () -> String?): Pair<AuthApiService, CameraApiService> {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val token = getToken()
                val request = if (token != null) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                } else {
                    chain.request()
                }
                val response = chain.proceed(request)
                response
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return Pair(
            retrofit.create(AuthApiService::class.java),
            retrofit.create(CameraApiService::class.java)
        )
    }
}
