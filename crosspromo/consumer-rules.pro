# Keep kotlinx.serialization models
-keepclassmembers class com.hartmann.crosspromo.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.hartmann.crosspromo.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Install Referrer AIDL
-keep class com.android.installreferrer.api.** { *; }
