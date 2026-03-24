-keepattributes Signature
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.autolyrics.lyrics.LrcLibClient$LrcLibResponse { *; }
-keep class com.autolyrics.lyrics.LrcLibClient$LrcLibSearchResult { *; }
-keep class com.autolyrics.lyrics.SyncLrcClient$SyncLrcResponse { *; }
-keep class com.autolyrics.lyrics.LyricsCache$CachedResult { *; }
-keep class com.autolyrics.lyrics.LyricsCache$CachedLine { *; }
-keep class com.autolyrics.lyrics.LyricsCache$CachedWord { *; }

# MediaBrowserService
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
