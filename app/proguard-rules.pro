# ProGuard Rules
-keep class com.example.myandroid.MainActivity { *; }
-dontwarn android.hardware.**
-dontwarn androidx.test.**

# Compose Rules
-keep class androidx.compose.material3.** { *; }
-dontwarn com.google.errorprone.annotations.**