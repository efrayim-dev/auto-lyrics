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
    private var lastQueueTrackTitle: String? = null

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
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                items.add(buildTextItem("error", "Error loading lyrics"))
            }
            LyricsStatus.FOUND -> {
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
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
        }

        state.albumArt?.let { art ->
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
        }

        val currentLine = state.lines.getOrNull(state.currentIndex)
        if (currentLine != null) {
            metaBuilder.putString(
                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                currentLine.text
            )
        } else {
            val subtitle = when (state.status) {
                LyricsStatus.LOADING -> "Loading lyrics…"
                LyricsStatus.NOT_FOUND -> "No lyrics found"
                LyricsStatus.ERROR -> "Error loading lyrics"
                LyricsStatus.PLAIN_ONLY -> state.lines.firstOrNull()?.text ?: ""
                else -> ""
            }
            if (subtitle.isNotBlank()) {
                metaBuilder.putString(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                    subtitle
                )
            }
        }

        mediaSession.setMetadata(metaBuilder.build())

        val trackTitle = state.track?.title
        if (trackTitle != lastQueueTrackTitle &&
            (state.status == LyricsStatus.FOUND || state.status == LyricsStatus.PLAIN_ONLY)
        ) {
            lastQueueTrackTitle = trackTitle
            val queueItems = state.lines.mapIndexed { i, line ->
                MediaSessionCompat.QueueItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId("q_$i")
                        .setTitle(line.text.ifBlank { "♪" })
                        .build(),
                    i.toLong()
                )
            }
            mediaSession.setQueue(queueItems)
            mediaSession.setQueueTitle("Lyrics")
        } else if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) {
            if (lastQueueTrackTitle != null) {
                lastQueueTrackTitle = null
                mediaSession.setQueue(emptyList())
            }
        }

        if (state.currentIndex >= 0) {
            mediaSession.setPlaybackState(buildPlaybackState(state, state.currentIndex.toLong()))
        } else {
            mediaSession.setPlaybackState(buildPlaybackState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN))
        }
    }

    private fun buildPlaybackState(state: LyricsState, activeQueueItemId: Long): PlaybackStateCompat {
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
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            )
            .setActiveQueueItemId(activeQueueItemId)
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
    }
}
