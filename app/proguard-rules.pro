# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep SponsorBlock data classes
-keep class com.ytextapp.extensions.SponsorBlockClient$Segment { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
