package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object SyncLrcClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://synclrc.tharuk.pro"
    private const val USER_AGENT = "AutoLyrics v1.4 (https://github.com/efrayim-dev/auto-lyrics)"

    data class SyncLrcResponse(
        @SerializedName("id") val id: String?,
        @SerializedName("track") val track: String?,
        @SerializedName("artist") val artist: String?,
        @SerializedName("lyrics") val lyrics: String?,
        @SerializedName("type") val type: String?
    )

    enum class LyricsType(val value: String) {
        KARAOKE("karaoke"),
        SYNCED("synced"),
        PLAIN("plain")
    }

    data class SyncLrcResult(
        val lyrics: String,
        val type: LyricsType
    )

    fun getLyrics(trackName: String, artistName: String): SyncLrcResult? {
        for (type in LyricsType.entries) {
            val result = fetchLyrics(trackName, artistName, type)
            if (result != null) return result
        }
        return null
    }

    private fun fetchLyrics(
        trackName: String,
        artistName: String,
        type: LyricsType
    ): SyncLrcResult? {
        val url = "$BASE_URL/lyrics".toHttpUrl().newBuilder()
            .addQueryParameter("track", trackName)
            .addQueryParameter("artist", artistName)
            .addQueryParameter("type", type.value)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val parsed = gson.fromJson(body, SyncLrcResponse::class.java) ?: return@use null
                val lyrics = parsed.lyrics
                if (lyrics.isNullOrBlank()) return@use null
                val actualType = when (parsed.type) {
                    "karaoke" -> LyricsType.KARAOKE
                    "synced" -> LyricsType.SYNCED
                    "plain" -> LyricsType.PLAIN
                    else -> type
                }
                SyncLrcResult(lyrics, actualType)
            }
        } catch (_: Exception) {
            null
        }
    }
}
