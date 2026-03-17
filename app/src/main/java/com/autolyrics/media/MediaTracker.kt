package com.autolyrics.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentLyricLine()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    fun getCurrentPositionMs(): Long {
        if (!_state.value.isPlaying) return lastPositionMs
        val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
        return lastPositionMs + (elapsed * playbackSpeed).toLong()
    }

    private fun updateCurrentLyricLine() {
        val lines = _state.value.lines
        if (lines.isEmpty()) return

        val posMs = getCurrentPositionMs()
        var newIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newIndex = i
            } else {
                break
            }
        }

        if (newIndex != _state.value.currentIndex) {
            _state.value = _state.value.copy(currentIndex = newIndex)
        }
    }

    fun onMediaSessionChanged(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            _state.value = LyricsState()
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val newTrack = TrackInfo(title, artist, album, duration)

        if (newTrack.title == _state.value.track?.title &&
            newTrack.artist == _state.value.track?.artist
        ) return

        _state.value = _state.value.copy(
            track = newTrack,
            lines = emptyList(),
            currentIndex = -1,
            status = LyricsStatus.LOADING
        )
        fetchLyrics(newTrack)
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val durationSec = if (track.durationMs > 0) {
                    (track.durationMs / 1000).toInt()
                } else 0

                val result = LrcLibClient.getLyrics(
                    trackName = track.title,
                    artistName = track.artist,
                    albumName = track.album,
                    durationSec = durationSec
                )

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result?.syncedLyrics != null) {
                        val lines = LrcParser.parse(result.syncedLyrics)
                        _state.value = _state.value.copy(
                            lines = lines,
                            status = LyricsStatus.FOUND
                        )
                        updateCurrentLyricLine()
                    } else if (result?.plainLyrics != null) {
                        val lines = result.plainLyrics.lines()
                            .filter { it.isNotBlank() }
                            .map { text ->
                                com.autolyrics.model.LyricLine(0L, text)
                            }
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            status = LyricsStatus.PLAIN_ONLY
                        )
                    } else {
                        _state.value = _state.value.copy(status = LyricsStatus.NOT_FOUND)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(status = LyricsStatus.ERROR)
                    }
                }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
