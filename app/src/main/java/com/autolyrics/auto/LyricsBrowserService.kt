package com.autolyrics.auto

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var displayedWindowStart = -1
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var lastSubtitleText: String? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaKaraokeEnabled = true
    private var aaOffsetMs = 0L

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> {
                    aaKaraokeEnabled = sp.getBoolean(key, true)
                    forceRefresh()
                }
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
            }
        }

    companion object {
        private const val ROOT_ID = "root"
        private const val WINDOW_SIZE = 4
        private const val NOTIFY_THROTTLE_MS = 800L
        private const val BROWSE_KARAOKE_WINDOW_MS = 1000L
        private const val SUBTITLE_KARAOKE_WINDOW_MS = 400L
        private const val SESSION_REFRESH_MS = 2500L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaKaraokeEnabled = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(300)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(NOTIFY_THROTTLE_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.track != null) {
                    mediaSession.isActive = true
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Error loading lyrics"))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                            .coerceIn(0, state.lines.size - 1)
                    } else { 0 }

                    val half = WINDOW_SIZE / 2
                    val winStart = maxOf(0, estimatedIdx - half)
                    val winEnd = minOf(state.lines.size, winStart + WINDOW_SIZE)
                    val adjStart = maxOf(0, winEnd - WINDOW_SIZE)

                    for (i in adjStart until winEnd) {
                        val prefix = if (i == estimatedIdx) "▶  " else "    "
                        val text = state.lines[i].text.ifBlank { "♪" }
                        items.add(buildTextItem("line_$i", "$prefix$text"))
                    }
                }
            }
        }

        result.sendResult(items)
    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val subtitle = buildString {
            append(track.artist)
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { -1L }
            if (posMs >= 0 && track.durationMs > 0) {
                append("  ·  ")
                append(formatTime(posMs))
                append(" / ")
                append(formatTime(track.durationMs))
            }
        }

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(subtitle)

        state.albumArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", "♪"))
            return
        }

        val posMs = getAaPositionMs()
        val aaCurrentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)
        val half = WINDOW_SIZE / 2

        val windowStart = maxOf(0, aaCurrentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)

        for (i in adjustedStart until windowEnd) {
            val line = lines[i]
            val isCurrent = i == aaCurrentIdx
            val prefix = if (isCurrent) "▶  " else "    "

            val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                line.text.ifBlank { "♪" }
            }

            items.add(buildTextItem("line_$i", "$prefix$text"))
        }
    }

    private fun buildTextItem(id: String, text: String): MediaBrowserCompat.MediaItem {
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(text)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    private fun buildKaraokeText(line: LyricLine, posMs: Long, windowMs: Long): String {
        val words = line.words
        if (words.isEmpty()) return line.text

        var currentIdx = -1
        for (i in words.indices) {
            if (words[i].timeMs <= posMs) currentIdx = i
            else break
        }
        if (currentIdx < 0) return line.text

        var endIdx = currentIdx
        for (i in (currentIdx + 1) until words.size) {
            if (words[i].timeMs <= posMs + windowMs) endIdx = i
            else break
        }

        val sb = StringBuilder()
        for (i in words.indices) {
            if (i == currentIdx) sb.append("【")
            sb.append(words[i].text)
            if (i == endIdx) sb.append("】")
            if (i < words.size - 1) sb.append(" ")
        }
        return sb.toString()
    }

    private fun getSubtitleText(state: LyricsState): String {
        val posMs = getAaPositionMs()

        if (state.status == LyricsStatus.FOUND) {
            val lineIdx = findLineIndex(state.lines, posMs)
            val line = state.lines.getOrNull(lineIdx)
            if (line != null) {
                return if (aaKaraokeEnabled && line.words.isNotEmpty()) {
                    buildKaraokeText(line, posMs, SUBTITLE_KARAOKE_WINDOW_MS)
                } else {
                    line.text
                }
            }
        }

        if (state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
            val durationMs = state.track?.durationMs ?: 0
            val idx = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                    .coerceIn(0, state.lines.size - 1)
            } else { 0 }
            return state.lines[idx].text
        }

        return when (state.status) {
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
    }

    // --- MediaSession management ---

    private fun buildBaseMetadata(state: LyricsState, includeArt: Boolean = true): MediaMetadataCompat.Builder {
        val metaBuilder = MediaMetadataCompat.Builder()

        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            if (track.durationMs > 0) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }

            val displayTitle = if (track.artist.isNotBlank()) {
                "${track.title} — ${track.artist}"
            } else {
                track.title
            }
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
        }

        if (includeArt) {
            state.albumArt?.let { art ->
                metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                lastAlbumArt = art
            }
        }

        return metaBuilder
    }

    private fun updateMediaSession(state: LyricsState) {
        val trackChanged = state.track?.title != displayedTrackTitle
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
        }

        val artChanged = state.albumArt !== lastAlbumArt
        val metaBuilder = buildBaseMetadata(state, includeArt = artChanged)

        val subtitleText = getSubtitleText(state)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        lastSubtitleText = subtitleText

        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        if (!state.isPlaying) return
        if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) return

        val subtitleText = getSubtitleText(state)
        if (subtitleText == lastSubtitleText) return
        lastSubtitleText = subtitleText

        val metaBuilder = buildBaseMetadata(state, includeArt = false)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        mediaSession.setMetadata(metaBuilder.build())
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val position = try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(val start: Int, val end: Int, val currentIdx: Int)

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val currentIdx = if (state.status == LyricsStatus.PLAIN_ONLY) {
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt()
                    .coerceIn(0, lines.size - 1)
            } else 0
        } else {
            val posMs = getAaPositionMs()
            findLineIndex(lines, posMs).coerceAtLeast(0)
        }

        val half = WINDOW_SIZE / 2
        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)
        return WindowInfo(adjustedStart, windowEnd, currentIdx)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        lastNotifyTime = 0L
        notifyChildrenChanged(ROOT_ID)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            notifyChildrenChanged(ROOT_ID)
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx

        val karaokeActive = aaKaraokeEnabled &&
            state.status == LyricsStatus.FOUND &&
            state.lines.getOrNull(state.currentIndex)?.words?.isNotEmpty() == true

        if (!windowChanged && !lineChanged && !statusChanged && !karaokeActive) return

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedStatus = state.status

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyChildrenChanged(ROOT_ID)
        } else if (!pendingNotify) {
            pendingNotify = true
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                notifyChildrenChanged(ROOT_ID)
            }, NOTIFY_THROTTLE_MS - elapsed)
        }
    }

    // --- Transport controls ---

    private fun getActiveMediaController(): MediaController? {
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            val component = android.content.ComponentName(
                this, com.autolyrics.media.MediaListenerService::class.java
            )
            sessionManager.getActiveSessions(component)
                .firstOrNull { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessionManager.getActiveSessions(component)
                    .firstOrNull { it.packageName != packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            getActiveMediaController()?.transportControls?.play()
        }

        override fun onPause() {
            getActiveMediaController()?.transportControls?.pause()
        }

        override fun onSkipToNext() {
            getActiveMediaController()?.transportControls?.skipToNext()
        }

        override fun onSkipToPrevious() {
            getActiveMediaController()?.transportControls?.skipToPrevious()
        }

        override fun onStop() {
            getActiveMediaController()?.transportControls?.stop()
        }

        override fun onSeekTo(pos: Long) {
            getActiveMediaController()?.transportControls?.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null || !mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                getActiveMediaController()?.transportControls?.seekTo(line.timeMs)
            }
        }
    }
}
