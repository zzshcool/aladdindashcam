# AladdinDashCam Proguard Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    native <methods>;
}

# The OEM engine looks up Java classes, fields and callbacks by their original names.
-keep class com.generalplus.ffmpegLib.** { *; }
-keepclassmembers class com.aladdin.dashcam.media.VideoExporter$* {
    boolean isCancelled();
    void onProgress(long);
    android.graphics.Bitmap renderOverlay(long, int, int);
}

# Preserve data models and serialization
-keep class com.aladdin.dashcam.model.** { *; }
-keep class com.aladdin.dashcam.driver.** { *; }

# Google Play Services & Google Maps SDK
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.maps.**

# OkHttp3
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# AndroidX Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
