package com.autolyrics.lyrics

import android.content.Context
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricWord
import com.autolyrics.model.LyricsStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class LyricsCache(context: Context) {

    private val cacheDir = File(context.filesDir, "lyrics_cache")
    private val gson = Gson()

    init {
        cacheDir.mkdirs()
    }

    data class CachedResult(
        val lines: List<CachedLine>,
        val status: String,
        val source: String,
        val timestamp: Long
    )

    data class CachedLine(
        val timeMs: Long,
        val text: String,
        val words: List<CachedWord>
    )

    data class CachedWord(
        val timeMs: Long,
        val text: String
    )

    fun get(title: String, artist: String): Triple<List<LyricLine>, LyricsStatus, String>? {
        val file = cacheFile(title, artist)
        if (!file.exists()) return null

        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            val status = try {
                LyricsStatus.valueOf(cached.status)
            } catch (_: Exception) {
                return null
            }
            val lines = cached.lines.map { cl ->
                LyricLine(
                    timeMs = cl.timeMs,
                    text = cl.text,
                    words = cl.words.map { cw -> LyricWord(cw.timeMs, cw.text) }
                )
            }
            Triple(lines, status, cached.source)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun put(title: String, artist: String, lines: List<LyricLine>, status: LyricsStatus, source: String) {
        try {
            val cached = CachedResult(
                lines = lines.map { line ->
                    CachedLine(
                        timeMs = line.timeMs,
                        text = line.text,
                        words = line.words.map { w -> CachedWord(w.timeMs, w.text) }
                    )
                },
                status = status.name,
                source = source,
                timestamp = System.currentTimeMillis()
            )
            val file = cacheFile(title, artist)
            file.writeText(gson.toJson(cached))
        } catch (_: Exception) {
            // cache write failures are non-fatal
        }
    }

    fun getAge(title: String, artist: String): Long {
        val file = cacheFile(title, artist)
        if (!file.exists()) return Long.MAX_VALUE
        return try {
            val json = file.readText()
            val cached = gson.fromJson(json, CachedResult::class.java)
            System.currentTimeMillis() - cached.timestamp
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    private fun cacheFile(title: String, artist: String): File {
        val key = "${title.lowercase().trim()}|${artist.lowercase().trim()}"
        val hash = key.hashCode().toUInt().toString(16)
        return File(cacheDir, "$hash.json")
    }
}
