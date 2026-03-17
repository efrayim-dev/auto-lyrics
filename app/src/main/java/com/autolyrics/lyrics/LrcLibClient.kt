package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object LrcLibClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "AutoLyrics v1.0 (https://github.com/user/auto-lyrics)"

    data class LrcLibResponse(
        @SerializedName("id") val id: Int?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("albumName") val albumName: String?,
        @SerializedName("duration") val duration: Int?,
        @SerializedName("instrumental") val instrumental: Boolean?,
        @SerializedName("plainLyrics") val plainLyrics: String?,
        @SerializedName("syncedLyrics") val syncedLyrics: String?
    )

    fun getLyrics(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        if (durationSec > 0 && albumName.isNotBlank()) {
            val exact = getExact(trackName, artistName, albumName, durationSec)
            if (exact?.syncedLyrics != null) return exact
        }

        val searchResult = search(trackName, artistName)
        if (searchResult?.syncedLyrics != null) return searchResult

        if (durationSec > 0) {
            val exactNoAlbum = getExact(trackName, artistName, "", durationSec)
            if (exactNoAlbum?.syncedLyrics != null) return exactNoAlbum
        }

        return searchResult
    }

    private fun getExact(
        trackName: String,
        artistName: String,
        albumName: String,
        durationSec: Int
    ): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)
            .addQueryParameter("artist_name", artistName)
            .addQueryParameter("album_name", albumName)
            .addQueryParameter("duration", durationSec.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    gson.fromJson(response.body?.string(), LrcLibResponse::class.java)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun search(trackName: String, artistName: String): LrcLibResponse? {
        val urlBuilder = "$BASE_URL/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", trackName)

        if (artistName.isNotBlank()) {
            urlBuilder.addQueryParameter("artist_name", artistName)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val type = object : TypeToken<List<LrcLibResponse>>() {}.type
                    val results: List<LrcLibResponse> = gson.fromJson(response.body?.string(), type)
                    results.firstOrNull { it.syncedLyrics != null }
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
