# Keep WebSocket models and serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepattributes *Annotation*
-dontwarn org.java_websocket.**
-keep class org.java_websocket.** { *; }

# AWS SDK
-dontwarn aws.sdk.kotlin.**
-keep class aws.sdk.kotlin.** { *; }
-dontwarn aws.smithy.kotlin.**
-keep class aws.smithy.kotlin.** { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }
