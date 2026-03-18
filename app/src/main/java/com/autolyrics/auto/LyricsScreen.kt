package com.autolyrics.auto

import android.text.SpannableString
import android.text.Spanned
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
    private var displayedWordIndex = Int.MIN_VALUE
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrack: TrackInfo? = null
    private var lastWordRefreshTime = 0L

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    mediaTracker.state.collectLatest { newState ->
                        val lineChanged = newState.currentIndex != displayedIndex
                        val statusChanged = newState.status != displayedStatus
                        val trackChanged = newState.track?.title != displayedTrack?.title
                        val wordChanged = newState.currentWordIndex != displayedWordIndex

                        val now = System.currentTimeMillis()
                        val wordThrottleOk = now - lastWordRefreshTime >= 400

                        val shouldRefresh = lineChanged || statusChanged || trackChanged ||
                            (wordChanged && wordThrottleOk)

                        if (shouldRefresh) {
                            displayedIndex = newState.currentIndex
                            displayedWordIndex = newState.currentWordIndex
                            displayedStatus = newState.status
                            displayedTrack = newState.track
                            if (wordChanged) lastWordRefreshTime = now
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

        val title = buildTitle(state.track, state.source)

        return when (state.status) {
            LyricsStatus.LOADING -> buildLoadingTemplate(title)
            LyricsStatus.NOT_FOUND -> buildNotFoundTemplate(title)
            LyricsStatus.ERROR -> buildErrorTemplate(title)
            LyricsStatus.FOUND -> buildSyncedLyricsTemplate(title, state)
            LyricsStatus.PLAIN_ONLY -> buildPlainLyricsTemplate(title, state)
            LyricsStatus.NO_MEDIA -> buildNoMediaTemplate()
        }
    }

    private fun buildTitle(track: TrackInfo, source: String = ""): String {
        val artist = if (track.artist.isNotBlank()) " — ${track.artist}" else ""
        val srcTag = if (source.isNotBlank()) " [$source]" else ""
        return "${track.title}$artist$srcTag"
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
                        .setTitle("No lyrics available")
                        .addText("Lyrics not found for this track")
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

    private fun buildSyncedLyricsTemplate(title: String, state: LyricsState): Template {
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
            val line = lines[i]
            val isCurrentLine = i == currentIdx

            val rowTitle: CharSequence = if (isCurrentLine && line.words.isNotEmpty()) {
                buildKaraokeRowTitle(line.text, line.words, state.currentWordIndex, true)
            } else if (isCurrentLine) {
                "▶  ${line.text}"
            } else {
                "     ${line.text}"
            }

            paneBuilder.addRow(
                Row.Builder()
                    .setTitle(rowTitle)
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

    private fun buildKaraokeRowTitle(
        fullText: String,
        words: List<com.autolyrics.model.LyricWord>,
        currentWordIndex: Int,
        isCurrent: Boolean
    ): CharSequence {
        val prefix = if (isCurrent) "▶  " else "     "
        val display = "$prefix${fullText}"

        if (currentWordIndex < 0 || currentWordIndex >= words.size) {
            return display
        }

        val targetWord = words[currentWordIndex].text
        val prefixLen = prefix.length

        var searchFrom = 0
        var wordOccurrence = 0
        var highlightStart = -1

        var charPos = 0
        for (wi in 0 until words.size) {
            val wordText = words[wi].text
            val idx = fullText.indexOf(wordText, charPos)
            if (idx < 0) continue

            if (wi == currentWordIndex) {
                highlightStart = prefixLen + idx
                break
            }
            charPos = idx + wordText.length
        }

        if (highlightStart < 0) return display

        val highlightEnd = highlightStart + targetWord.length
        if (highlightEnd > display.length) return display

        val spannable = SpannableString(display)
        spannable.setSpan(
            ForegroundCarColorSpan.create(CarColor.YELLOW),
            highlightStart, highlightEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    private fun buildPlainLyricsTemplate(title: String, state: LyricsState): Template {
        val lines = state.lines
        val listBuilder = ItemList.Builder()

        listBuilder.addItem(
            Row.Builder()
                .setTitle("ℹ  Lyrics are not synced to playback")
                .build()
        )

        val maxLines = minOf(lines.size, 100)
        for (i in 0 until maxLines) {
            val text = lines[i].text.ifBlank { "♪" }
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(text)
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setTitle(title)
            .setSingleList(listBuilder.build())
            .build()
    }
}
