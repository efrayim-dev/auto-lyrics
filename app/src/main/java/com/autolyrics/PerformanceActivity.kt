package com.autolyrics

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.launch

class PerformanceActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private lateinit var root: FrameLayout
    private lateinit var albumBg: ImageView
    private lateinit var albumArt: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var sourceLabel: TextView
    private lateinit var currentLine: TextView
    private lateinit var nextLine: TextView
    private lateinit var exitHint: TextView

    private var lastRenderedIndex = -1
    private var lastRenderedWord = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_performance)

        enterImmersiveMode()

        mediaTracker = MediaTracker.getInstance(this)

        root = findViewById(R.id.perf_root)
        albumBg = findViewById(R.id.perf_album_bg)
        albumArt = findViewById(R.id.perf_album_art)
        trackTitle = findViewById(R.id.perf_track_title)
        trackArtist = findViewById(R.id.perf_track_artist)
        sourceLabel = findViewById(R.id.perf_source)
        currentLine = findViewById(R.id.perf_current_line)
        nextLine = findViewById(R.id.perf_next_line)
        exitHint = findViewById(R.id.perf_exit_hint)

        root.setOnClickListener { finish() }

        exitHint.animate().alpha(0f).setStartDelay(3000).setDuration(1000).start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state -> render(state) }
            }
        }
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun render(state: LyricsState) {
        state.track?.let { track ->
            trackTitle.text = track.title
            trackArtist.text = track.artist.ifBlank { "" }
        }

        state.albumArt?.let { art ->
            albumBg.setImageBitmap(art)
            albumArt.setImageBitmap(art)
            albumArt.visibility = View.VISIBLE
        } ?: run {
            albumBg.setImageDrawable(null)
            albumArt.visibility = View.GONE
        }

        if (state.source.isNotBlank()) {
            sourceLabel.text = state.source
            sourceLabel.visibility = View.VISIBLE
        } else {
            sourceLabel.visibility = View.GONE
        }

        val colors = state.albumColors
        val accentColor = colors?.vibrant ?: DEFAULT_ACCENT

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                currentLine.text = "Play a song"
                nextLine.text = ""
            }
            LyricsStatus.LOADING -> {
                currentLine.text = "Loading lyrics…"
                nextLine.text = ""
            }
            LyricsStatus.NOT_FOUND -> {
                currentLine.text = "No lyrics found"
                nextLine.text = ""
            }
            LyricsStatus.ERROR -> {
                currentLine.text = "Error loading lyrics"
                nextLine.text = ""
            }
            LyricsStatus.PLAIN_ONLY -> {
                renderPlain(state)
            }
            LyricsStatus.FOUND -> {
                renderSynced(state, accentColor)
            }
        }
    }

    private fun renderPlain(state: LyricsState) {
        val lines = state.lines
        if (lines.isEmpty()) {
            currentLine.text = "♪"
            nextLine.text = ""
            return
        }

        val durationMs = state.track?.durationMs ?: 0
        val posMs = try {
            mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
        } catch (_: Exception) { 0L }

        val idx = if (durationMs > 0) {
            ((posMs.toFloat() / durationMs) * lines.size).toInt()
                .coerceIn(0, lines.size - 1)
        } else 0

        currentLine.text = lines[idx].text.ifBlank { "♪" }
        nextLine.text = lines.getOrNull(idx + 1)?.text ?: ""
    }

    private fun renderSynced(state: LyricsState, accentColor: Int) {
        val idx = state.currentIndex
        val wordIdx = state.currentWordIndex
        val lines = state.lines

        if (idx < 0 || idx >= lines.size) {
            currentLine.text = "♪"
            nextLine.text = ""
            return
        }

        val needsFullRedraw = idx != lastRenderedIndex || wordIdx != lastRenderedWord
        if (!needsFullRedraw) return

        lastRenderedIndex = idx
        lastRenderedWord = wordIdx

        val line = lines[idx]
        val hasKaraoke = line.words.isNotEmpty()

        if (hasKaraoke && wordIdx >= 0) {
            val ssb = SpannableStringBuilder()
            val baseSizePx = currentLine.textSize.toInt()
            val popSizePx = (baseSizePx * 1.5).toInt()
            val accentBg = setAlpha(accentColor, 0.25f)

            line.words.forEachIndexed { wi, word ->
                val start = ssb.length
                ssb.append(word.text)
                val end = ssb.length

                if (wi == wordIdx) {
                    ssb.setSpan(AbsoluteSizeSpan(popSizePx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(ForegroundColorSpan(accentColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(accentBg), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }

                if (wi < line.words.size - 1) ssb.append(" ")
            }
            currentLine.text = ssb
        } else {
            currentLine.text = line.text.ifBlank { "♪" }
        }

        val nextText = lines.getOrNull(idx + 1)?.text ?: ""
        if (nextLine.text.toString() != nextText) {
            nextLine.animate().alpha(0f).setDuration(120).withEndAction {
                nextLine.text = nextText
                nextLine.animate().alpha(1f).setDuration(200).start()
            }.start()
        }
    }

    companion object {
        private val DEFAULT_ACCENT = Color.parseColor("#FFD54F")

        private fun setAlpha(color: Int, alpha: Float): Int {
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}
