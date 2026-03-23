package com.autolyrics.auto

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

    companion object {
        private const val ROOT_ID = "root"
        private const val WINDOW_SIZE = 6
        private const val NOTIFY_THROTTLE_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

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
    }

    override fun onDestroy() {
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
                addProgressRow(state, items)
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
        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(track.artist)

        state.albumArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
        )
    }

    private fun addProgressRow(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return
        if (track.durationMs <= 0) return

        val posMs = try {
            mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
        } catch (_: Exception) {
            return
        }

        val posStr = formatTime(posMs)
        val durStr = formatTime(track.durationMs)
        val fraction = (posMs.toFloat() / track.durationMs).coerceIn(0f, 1f)
        val filled = (fraction * 20).toInt()
        val empty = 20 - filled
        val bar = "▬".repeat(filled) + "○" + "─".repeat(empty)

        items.add(buildTextItem("progress", "$bar  $posStr / $durStr"))
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

        val currentIdx = maxOf(0, state.currentIndex)
        val half = WINDOW_SIZE / 2

        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)

        for (i in adjustedStart until windowEnd) {
            val line = lines[i]
            val isCurrent = i == state.currentIndex
            val prefix = if (isCurrent) "▶  " else "    "
            val text = line.text.ifBlank { "♪" }
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

    private fun updateMediaSession(state: LyricsState) {
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

        val currentLine = state.lines.getOrNull(state.currentIndex)
        val displaySubtitle = if (currentLine != null) {
            currentLine.text
        } else {
            when (state.status) {
                LyricsStatus.LOADING -> "Loading lyrics…"
                LyricsStatus.NOT_FOUND -> "No lyrics found"
                LyricsStatus.ERROR -> "Error loading lyrics"
                LyricsStatus.PLAIN_ONLY -> state.lines.firstOrNull()?.text ?: ""
                else -> ""
            }
        }
        if (displaySubtitle.isNotBlank()) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                displaySubtitle
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

    private fun computeWindow(state: LyricsState): Pair<Int, Int> {
        val lines = state.lines
        if (lines.isEmpty()) return Pair(-1, -1)
        val currentIdx = maxOf(0, state.currentIndex)
        val half = WINDOW_SIZE / 2
        val windowStart = maxOf(0, currentIdx - half)
        val windowEnd = minOf(lines.size, windowStart + WINDOW_SIZE)
        val adjustedStart = maxOf(0, windowEnd - WINDOW_SIZE)
        return Pair(adjustedStart, windowEnd)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        val (winStart, winEnd) = computeWindow(state)
        val windowChanged = winStart != displayedWindowStart || winEnd != displayedWindowEnd

        if (!windowChanged && !statusChanged && !trackChanged) return

        displayedWindowStart = winStart
        displayedWindowEnd = winEnd
        displayedStatus = state.status
        displayedTrackTitle = state.track?.title

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
