# ProGuard Rules
-keep class com.example.myandroid.MainActivity { *; }
-dontwarn android.hardware.**
-dontwarn androidx.test.**

# Kotlin Standard Library & Coroutines (Required for Dynamic Payloads)
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }

# Compose Rules (Keep entire framework unobfuscated for Speedster payloads)
-keep class androidx.compose.** { *; }
-dontwarn com.google.errorprone.annotations.**

# Cortex Dynamic Payload Contract
-keep class com.example.myandroid.dynamic.DynamicEntry { *; }