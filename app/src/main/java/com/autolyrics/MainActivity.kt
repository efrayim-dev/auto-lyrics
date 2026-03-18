package com.autolyrics

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var tvTrack: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvDelay: TextView
    private var lastScrolledIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)

        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        tvTrack = findViewById(R.id.tv_track)
        tvSource = findViewById(R.id.tv_source)
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)
        tvDelay = findViewById(R.id.tv_delay)

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        findViewById<Button>(R.id.btn_delay_minus).setOnClickListener {
            mediaTracker.adjustOffset(-100)
        }
        findViewById<Button>(R.id.btn_delay_plus).setOnClickListener {
            mediaTracker.adjustOffset(100)
        }
        findViewById<Button>(R.id.btn_delay_reset).setOnClickListener {
            mediaTracker.resetOffset()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)

                    if (state.track != null) {
                        val artistText = if (state.track.artist.isNotBlank())
                            state.track.artist else "Unknown Artist"
                        tvTrack.text = "${state.track.title}\n$artistText"
                        tvTrack.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = ""
                        tvTrack.visibility = View.GONE
                    }

                    if (state.source.isNotBlank()) {
                        tvSource.text = state.source
                        tvSource.visibility = View.VISIBLE
                    } else {
                        tvSource.visibility = View.GONE
                    }

                    when (state.status) {
                        LyricsStatus.NO_MEDIA -> {
                            tvLyrics.text = "Play a song to see lyrics here.\n\nLyrics will also appear on Android Auto."
                        }
                        LyricsStatus.LOADING -> {
                            tvLyrics.text = "Loading lyrics…"
                        }
                        LyricsStatus.NOT_FOUND -> {
                            tvLyrics.text = "No lyrics found for this track."
                        }
                        LyricsStatus.ERROR -> {
                            tvLyrics.text = "Error loading lyrics.\nCheck your internet connection."
                        }
                        LyricsStatus.FOUND -> {
                            renderSyncedLyrics(state)
                        }
                        LyricsStatus.PLAIN_ONLY -> {
                            val sb = StringBuilder()
                            sb.append("ℹ  Lyrics are not synced to playback\n\n")
                            sb.append("─────────────────────\n\n")
                            state.lines.forEach { line ->
                                sb.append("${line.text}\n\n")
                            }
                            tvLyrics.text = sb.toString()
                            scrollView.scrollTo(0, 0)
                        }
                    }
                }
            }
        }
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        val sign = when {
            offsetMs > 0 -> "+"
            else -> ""
        }
        tvDelay.text = "Sync: ${sign}${offsetMs}ms"
    }

    private fun renderSyncedLyrics(state: LyricsState) {
        val ssb = SpannableStringBuilder()
        val hasKaraoke = state.lines.any { it.words.isNotEmpty() }

        state.lines.forEachIndexed { i, line ->
            val isCurrentLine = i == state.currentIndex
            val lineStart = ssb.length

            if (isCurrentLine) {
                ssb.append("▶  ")
            } else {
                ssb.append("    ")
            }

            if (isCurrentLine && hasKaraoke && line.words.isNotEmpty()) {
                val wordsStart = ssb.length
                line.words.forEachIndexed { wi, word ->
                    val wordStart = ssb.length
                    ssb.append(word.text)
                    val wordEnd = ssb.length

                    if (wi == state.currentWordIndex) {
                        ssb.setSpan(
                            StyleSpan(Typeface.BOLD),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            ForegroundColorSpan(HIGHLIGHT_COLOR),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            BackgroundColorSpan(HIGHLIGHT_BG_COLOR),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    if (wi < line.words.size - 1) ssb.append(" ")
                }
                val wordsEnd = ssb.length
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart, wordsEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                ssb.append(line.text)
                if (isCurrentLine) {
                    ssb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        lineStart, ssb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            if (!isCurrentLine) {
                ssb.setSpan(
                    ForegroundColorSpan(DIM_COLOR),
                    lineStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            ssb.append("\n\n")
        }

        tvLyrics.text = ssb

        if (state.currentIndex != lastScrolledIndex && state.currentIndex >= 0) {
            lastScrolledIndex = state.currentIndex
            autoScrollToCurrentLine(state.currentIndex, state.lines.size)
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.text = "✓  Notification access granted"
            tvStatus.setBackgroundResource(R.drawable.bg_status_ok)
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "⚠  Notification access required"
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            btnPermission.visibility = View.VISIBLE
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val component = ComponentName(this, MediaListenerService::class.java)
        return flat?.contains(component.flattenToString()) == true
    }

    private fun autoScrollToCurrentLine(currentIndex: Int, totalLines: Int) {
        if (totalLines == 0) return
        scrollView.post {
            val targetY = (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }

    companion object {
        private val HIGHLIGHT_COLOR = Color.parseColor("#FFD54F")
        private val HIGHLIGHT_BG_COLOR = Color.parseColor("#33FFD54F")
        private val DIM_COLOR = Color.parseColor("#99FFFFFF")
    }
}
