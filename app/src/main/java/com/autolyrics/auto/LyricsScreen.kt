package com.autolyrics.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsScreen(carContext: CarContext) : Screen(carContext) {

    private val mediaTracker = MediaTracker.getInstance(carContext)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var displayedIndex = Int.MIN_VALUE
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrack: TrackInfo? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    mediaTracker.state.collectLatest { newState ->
                        val shouldRefresh =
                            newState.currentIndex != displayedIndex ||
                            newState.status != displayedStatus ||
                            newState.track?.title != displayedTrack?.title

                        if (shouldRefresh) {
                            displayedIndex = newState.currentIndex
                            displayedStatus = newState.status
                            displayedTrack = newState.track
                            invalidate()
                        }
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.coroutineContext.cancelChildren()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val state = mediaTracker.state.value

        if (state.track == null || state.status == LyricsStatus.NO_MEDIA) {
            return buildNoMediaTemplate()
        }

        val title = buildTitle(state.track)

        return when (state.status) {
            LyricsStatus.LOADING -> buildLoadingTemplate(title)
            LyricsStatus.NOT_FOUND -> buildNotFoundTemplate(title)
            LyricsStatus.ERROR -> buildErrorTemplate(title)
            LyricsStatus.FOUND -> buildLyricsTemplate(title, state)
            LyricsStatus.NO_MEDIA -> buildNoMediaTemplate()
        }
    }

    private fun buildTitle(track: TrackInfo): String {
        val artist = if (track.artist.isNotBlank()) " — ${track.artist}" else ""
        return "${track.title}$artist"
    }

    private fun buildNoMediaTemplate(): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("Play a song to see lyrics")
                        .addText("Open any music app and start playing")
                        .build()
                )
                .build()
        )
            .setTitle("Auto Lyrics")
            .build()
    }

    private fun buildLoadingTemplate(title: String): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .setLoading(true)
                .build()
        )
            .setTitle(title)
            .build()
    }

    private fun buildNotFoundTemplate(title: String): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("No synced lyrics available")
                        .addText("Lyrics not found on LRCLIB for this track")
                        .build()
                )
                .build()
        )
            .setTitle(title)
            .build()
    }

    private fun buildErrorTemplate(title: String): Template {
        return PaneTemplate.Builder(
            Pane.Builder()
                .addRow(
                    Row.Builder()
                        .setTitle("Could not load lyrics")
                        .addText("Check your internet connection")
                        .build()
                )
                .build()
        )
            .setTitle(title)
            .build()
    }

    private fun buildLyricsTemplate(title: String, state: LyricsState): Template {
        val lines = state.lines
        val currentIdx = state.currentIndex
        val paneBuilder = Pane.Builder()

        if (lines.isEmpty()) {
            paneBuilder.addRow(Row.Builder().setTitle("♪").build())
            return PaneTemplate.Builder(paneBuilder.build())
                .setTitle(title)
                .build()
        }

        val windowStart = maxOf(0, currentIdx - 1)
        val windowEnd = minOf(lines.size, windowStart + 4)
        val adjustedStart = maxOf(0, windowEnd - 4)

        var rowCount = 0
        for (i in adjustedStart until windowEnd) {
            if (rowCount >= 4) break
            val text = lines[i].text
            val displayText = if (i == currentIdx) "▶  $text" else "     $text"
            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(displayText)
                    .build()
            )
            rowCount++
        }

        if (rowCount == 0) {
            paneBuilder.addRow(Row.Builder().setTitle("♪").build())
        }

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle(title)
            .build()
    }
}
