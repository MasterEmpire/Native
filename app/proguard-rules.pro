# ProGuard Rules
-keep class com.example.myandroid.MainActivity { *; }
-dontwarn android.hardware.**
-dontwarn androidx.test.**

# Kotlin Standard Library & Coroutines (Required for Dynamic Payloads)
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Compose Rules (Keep entire framework unobfuscated for Speedster payloads)
-keep class androidx.compose.** { *; }
-keep class androidx.activity.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-dontwarn com.google.errorprone.annotations.**

# Cortex Dynamic Payload Contract
-keep class com.example.myandroid.dynamic.DynamicEntry { *; }
-keep class com.example.myandroid.DynamicUIManager { *; }
-keep class com.example.myandroid.AgentActivity { *; }

# Networking (Required for dynamic WebSockets via Speedster payloads)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**