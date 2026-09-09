# Sightline ProGuard rules

# Keep TFLite model loading
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**

# Keep ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
