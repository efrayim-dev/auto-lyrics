-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.autolyrics.lyrics.LrcLibClient$LrcLibResponse { *; }
-keep class com.autolyrics.lyrics.LrcLibClient$LrcLibSearchResult { *; }

# Car App Library
-keep class androidx.car.app.** { *; }
