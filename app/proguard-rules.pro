# Peek — ProGuard/R8 kuralları
#
# Release build'te WebRTC native lib'leri, Gson modelleri ve OkHttp
# için gerekli sınıfların shrink/obfuscate edilmesini engeller.

# WebRTC (stream-webrtc-android) — native peer connection ve track sınıfları
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# Gson — signaling modelleri (reflection ile serialize/deserialize)
-keep class com.peek.app.data.models.** { *; }
-keepclassmembers class com.peek.app.data.models.** { *; }

# OkHttp / Okio — uyarıları sustur
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin coroutines — state machine sınıfları
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# DataStore — internal sınıflar
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.** { *; }

# AndroidX Lifecycle
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class androidx.lifecycle.** { *; }

# Gson TypeAdapter'lar için (gerekirse)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
