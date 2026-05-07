# Keep JNI entry points used by whisper.cpp bridge
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.charlesh.captionburn.data.transcription.WhisperJni { *; }
-keep class com.charlesh.captionburn.data.transcription.WhisperEngine { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# FFmpegKit
-keep class com.arthenica.** { *; }
-dontwarn com.arthenica.**

# WorkManager/Hilt generated workers used by class name in runtime metadata.
-keep class androidx.work.impl.background.systemjob.SystemJobService { *; }
-keep class * extends androidx.work.ListenableWorker
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# Hilt + Kotlin Serialization defaults (covered by their plugins, but be safe)
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.Serializable <fields>;
}

# Compose tooling Looper preview helpers
-dontwarn androidx.compose.**
