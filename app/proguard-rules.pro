# Keep WebRTC classes
-keep class org.webrtc.** { *; }

# Keep Retrofit model classes
-keep class com.ipcam.app.data.model.** { *; }

# Keep Auth0 JWT
-keep class com.auth0.jwt.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
