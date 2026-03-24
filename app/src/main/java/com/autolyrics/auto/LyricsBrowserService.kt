package com.autolyrics.auto

import android.content.SharedPreferences
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
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var lastSubtitleText: String? = null

    private var aaKaraokeEnabled = true
    private var aaOffsetMs = 0L

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> aaKaraokeEnabled = sp.getBoolean(key, true)
                "aa_offset_ms" -> aaOffsetMs = sp.getLong(key, 0L)
            }
        }

    companion object {
        private const val ROOT_ID = "root"
        private const val WINDOW_SIZE = 4
        private const val NOTIFY_THROTTLE_MS = 800L
        private const val BROWSE_KARAOKE_WINDOW_MS = 1000L
        private const val SUBTITLE_KARAOKE_WINDOW_MS = 400L
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
                items.add(buildTextItem("info", "ℹ  Lyrics are not synced to playback"))
                val maxLines = minOf(state.lines.size, WINDOW_SIZE - 1)
                for (i in 0 until maxLines) {
                    val text = state.lines[i].text.ifBlank { "♪" }
                    items.add(buildTextItem("line_$i", text))
                }
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
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
        val lineIdx = if (state.status == LyricsStatus.FOUND) {
            findLineIndex(state.lines, posMs)
        } else {
            -1
        }

        val line = state.lines.getOrNull(lineIdx)
        if (line != null) {
            return if (aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, posMs, SUBTITLE_KARAOKE_WINDOW_MS)
            } else {
                line.text
            }
        }

        return when (state.status) {
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            LyricsStatus.PLAIN_ONLY -> state.lines.firstOrNull()?.text ?: ""
            else -> ""
        }
    }

    // --- MediaSession management ---

    private fun buildBaseMetadata(state: LyricsState): MediaMetadataCompat.Builder {
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

        state.albumArt?.let { art ->
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        }

        return metaBuilder
    }

    private fun updateMediaSession(state: LyricsState) {
        if (state.track?.title != displayedTrackTitle) {
            mediaSession.isActive = true
            lastSubtitleText = null
        }

        val metaBuilder = buildBaseMetadata(state)

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
        if (!state.isPlaying || state.status != LyricsStatus.FOUND) return

        val subtitleText = getSubtitleText(state)
        if (subtitleText == lastSubtitleText) return
        lastSubtitleText = subtitleText

        val metaBuilder = buildBaseMetadata(state)
        if (subtitleText.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                subtitleText
            )
        }
        mediaSession.setMetadata(metaBuilder.build())
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

    private fun computeWindow(state: LyricsState): Pair<Int, Int> {
        val lines = state.lines
        if (lines.isEmpty()) return Pair(-1, -1)
        val posMs = getAaPositionMs()
        val currentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)
        val half = WINDOW_SIZE / 2
        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)
        return Pair(adjustedStart, windowEnd)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            notifyChildrenChanged(ROOT_ID)
            return
        }

        val (winStart, winEnd) = computeWindow(state)
        val windowChanged = winStart != displayedWindowStart || winEnd != displayedWindowEnd

        val karaokeActive = aaKaraokeEnabled &&
            state.status == LyricsStatus.FOUND &&
            state.lines.getOrNull(state.currentIndex)?.words?.isNotEmpty() == true

        if (!windowChanged && !statusChanged && !karaokeActive) return

        displayedWindowStart = winStart
        displayedWindowEnd = winEnd
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
