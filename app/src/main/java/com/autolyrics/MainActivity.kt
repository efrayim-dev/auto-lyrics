package com.autolyrics

import android.Manifest
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.autolyrics.media.MediaListenerService
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.AlbumColors
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.util.AudioSyncHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var rootLayout: LinearLayout
    private lateinit var appBar: LinearLayout
    private lateinit var tvAppTitle: TextView
    private lateinit var tvAppSubtitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var ivAlbumArt: ImageView
    private lateinit var tvTrack: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var delayBar: LinearLayout
    private lateinit var tvDelay: TextView
    private lateinit var divider: View
    private lateinit var fontSettingsPanel: LinearLayout
    private lateinit var tvFontSize: TextView
    private lateinit var switchAaKaraoke: SwitchCompat
    private lateinit var tvAaDelay: TextView
    private lateinit var btnJumpToCurrent: Button
    private lateinit var prefs: SharedPreferences
    private var lastScrolledIndex = -1
    private var currentColors: AlbumColors? = null
    private var lyricsFontSizeSp = 16
    private var lyricsFontFamily = "sans-serif"
    private var aaOffsetMs = 0L
    private var userScrolling = false
    private var userTouching = false

    private var plainScrollAnimator: ValueAnimator? = null
    private var lastPlainTrackTitle: String? = null

    private lateinit var btnTapSync: Button
    private lateinit var btnAutoSync: Button
    private lateinit var tvSyncStatus: TextView
    private var tapSyncActive = false
    private var tapSyncTargetLineIndex = -1
    private var tapSyncOffsets = mutableListOf<Long>()
    private var audioSyncHelper: AudioSyncHelper? = null

    private val fontButtons = mutableMapOf<String, Button>()
    private val scrollResetRunnable = Runnable {
        userScrolling = false
        btnJumpToCurrent.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)
        prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)

        rootLayout = findViewById(R.id.root_layout)
        appBar = findViewById(R.id.layout_app_bar)
        tvAppTitle = findViewById(R.id.tv_app_title)
        tvAppSubtitle = findViewById(R.id.tv_app_subtitle)
        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        ivAlbumArt = findViewById(R.id.iv_album_art)
        tvTrack = findViewById(R.id.tv_track)
        tvSource = findViewById(R.id.tv_source)
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)
        delayBar = findViewById(R.id.layout_delay)
        tvDelay = findViewById(R.id.tv_delay)
        divider = findViewById(R.id.divider)

        ivAlbumArt.clipToOutline = true
        btnJumpToCurrent = findViewById(R.id.btn_jump_to_current)

        setupScrollDetection()

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

        fontSettingsPanel = findViewById(R.id.layout_font_settings)
        tvFontSize = findViewById(R.id.tv_font_size)

        lyricsFontSizeSp = prefs.getInt("lyrics_font_size", 16)
        lyricsFontFamily = prefs.getString("lyrics_font_family", "sans-serif") ?: "sans-serif"

        applyFontSettings()

        findViewById<ImageButton>(R.id.btn_settings_toggle).setOnClickListener {
            fontSettingsPanel.visibility = if (fontSettingsPanel.visibility == View.VISIBLE)
                View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.btn_font_size_minus).setOnClickListener {
            if (lyricsFontSizeSp > 12) {
                lyricsFontSizeSp -= 2
                saveFontSettings()
                applyFontSettings()
            }
        }
        findViewById<Button>(R.id.btn_font_size_plus).setOnClickListener {
            if (lyricsFontSizeSp < 28) {
                lyricsFontSizeSp += 2
                saveFontSettings()
                applyFontSettings()
            }
        }

        val btnSans = findViewById<Button>(R.id.btn_font_sans)
        val btnSerif = findViewById<Button>(R.id.btn_font_serif)
        val btnMono = findViewById<Button>(R.id.btn_font_mono)
        val btnCursive = findViewById<Button>(R.id.btn_font_cursive)

        fontButtons["sans-serif"] = btnSans
        fontButtons["serif"] = btnSerif
        fontButtons["monospace"] = btnMono
        fontButtons["cursive"] = btnCursive

        btnSans.setOnClickListener { selectFont("sans-serif") }
        btnSerif.setOnClickListener { selectFont("serif") }
        btnMono.setOnClickListener { selectFont("monospace") }
        btnCursive.setOnClickListener { selectFont("cursive") }

        updateFontButtonHighlights()

        switchAaKaraoke = findViewById(R.id.switch_aa_karaoke)
        tvAaDelay = findViewById(R.id.tv_aa_delay)

        switchAaKaraoke.isChecked = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        updateAaDelayDisplay()

        switchAaKaraoke.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("aa_karaoke_enabled", isChecked).apply()
        }

        findViewById<Button>(R.id.btn_aa_delay_minus).setOnClickListener {
            aaOffsetMs -= 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_plus).setOnClickListener {
            aaOffsetMs += 100
            prefs.edit().putLong("aa_offset_ms", aaOffsetMs).apply()
            updateAaDelayDisplay()
        }
        findViewById<Button>(R.id.btn_aa_delay_reset).setOnClickListener {
            aaOffsetMs = 0L
            prefs.edit().putLong("aa_offset_ms", 0L).apply()
            updateAaDelayDisplay()
        }

        btnTapSync = findViewById(R.id.btn_tap_sync)
        btnAutoSync = findViewById(R.id.btn_auto_sync)
        tvSyncStatus = findViewById(R.id.tv_sync_status)

        btnTapSync.setOnClickListener { onTapSyncPressed() }
        btnAutoSync.setOnClickListener { onAutoSyncPressed() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()
                    updateDelayDisplay(state.offsetMs)
                    updateAlbumArt(state)
                    applyThemeColors(state.albumColors)

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

                    if (state.status != LyricsStatus.PLAIN_ONLY) {
                        stopPlainScroll()
                    } else {
                        plainScrollAnimator?.let { anim ->
                            if (state.isPlaying && anim.isPaused) anim.resume()
                            else if (!state.isPlaying && anim.isRunning) anim.pause()
                        }
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
                            stopPlainScroll()
                            val sb = StringBuilder()
                            sb.append("ℹ  Lyrics are not synced to playback\n\n")
                            sb.append("─────────────────────\n\n")
                            state.lines.forEach { line ->
                                sb.append("${line.text}\n\n")
                            }
                            tvLyrics.text = sb.toString()
                            startPlainScroll(state)
                        }
                    }
                }
            }
        }
    }

    private fun updateAlbumArt(state: LyricsState) {
        val art = state.albumArt
        if (art != null) {
            ivAlbumArt.setImageBitmap(art)
            ivAlbumArt.visibility = View.VISIBLE
        } else {
            ivAlbumArt.setImageDrawable(null)
            ivAlbumArt.visibility = View.GONE
        }
    }

    private fun applyThemeColors(colors: AlbumColors?) {
        if (colors == currentColors) return
        currentColors = colors

        if (colors != null) {
            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(colors.dominant, colors.dominantDark)
            )
            rootLayout.background = gradient

            appBar.setBackgroundColor(lighten(colors.dominant, 1.3f))
            delayBar.setBackgroundColor(lighten(colors.dominantDark, 1.2f))
            divider.setBackgroundColor(lighten(colors.dominant, 1.8f))

            tvAppTitle.setTextColor(colors.textPrimary)
            tvAppSubtitle.setTextColor(colors.textDim)
            tvTrack.setTextColor(colors.vibrant)
            tvLyrics.setTextColor(colors.textPrimary)
        } else {
            rootLayout.setBackgroundColor(DEFAULT_BG)
            appBar.setBackgroundColor(DEFAULT_APP_BAR)
            delayBar.setBackgroundColor(DEFAULT_DELAY_BAR)
            divider.setBackgroundColor(DEFAULT_DIVIDER)

            tvAppTitle.setTextColor(Color.parseColor("#E0E0FF"))
            tvAppSubtitle.setTextColor(Color.parseColor("#8888AA"))
            tvTrack.setTextColor(Color.parseColor("#BB86FC"))
            tvLyrics.setTextColor(Color.parseColor("#CCCCDD"))
        }
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun updateDelayDisplay(offsetMs: Long) {
        val sign = when {
            offsetMs > 0 -> "+"
            else -> ""
        }
        tvDelay.text = "Phone Sync: ${sign}${offsetMs}ms"
    }

    private fun renderSyncedLyrics(state: LyricsState) {
        val ssb = SpannableStringBuilder()
        val hasKaraoke = state.lines.any { it.words.isNotEmpty() }
        val colors = state.albumColors
        val highlightColor = colors?.vibrant ?: DEFAULT_HIGHLIGHT
        val highlightBg = setAlpha(highlightColor, 0.2f)
        val dimColor = colors?.textDim ?: DEFAULT_DIM

        state.lines.forEachIndexed { i, line ->
            val isCurrentLine = i == state.currentIndex
            val lineStart = ssb.length

            if (isCurrentLine) {
                ssb.append("▶  ")
            } else {
                ssb.append("    ")
            }

            if (isCurrentLine && hasKaraoke && line.words.isNotEmpty()) {
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
                            ForegroundColorSpan(highlightColor),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        ssb.setSpan(
                            BackgroundColorSpan(highlightBg),
                            wordStart, wordEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }

                    if (wi < line.words.size - 1) ssb.append(" ")
                }
                ssb.setSpan(
                    StyleSpan(Typeface.BOLD),
                    lineStart, ssb.length,
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
                    ForegroundColorSpan(dimColor),
                    lineStart, ssb.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            ssb.append("\n\n")
        }

        tvLyrics.text = ssb

        if (state.currentIndex != lastScrolledIndex && state.currentIndex >= 0) {
            lastScrolledIndex = state.currentIndex
            if (!userScrolling) {
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
    }

    override fun onDestroy() {
        audioSyncHelper?.stop()
        audioSyncHelper = null
        stopPlainScroll()
        super.onDestroy()
    }

    private fun updatePermissionUi() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            tvStatus.visibility = View.GONE
            btnPermission.visibility = View.GONE
        } else {
            tvStatus.text = "⚠  Notification access required"
            tvStatus.setBackgroundResource(R.drawable.bg_status_warn)
            tvStatus.visibility = View.VISIBLE
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollDetection() {
        scrollView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    userTouching = true
                    handler.removeCallbacks(scrollResetRunnable)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    if (userScrolling) {
                        handler.removeCallbacks(scrollResetRunnable)
                        handler.postDelayed(scrollResetRunnable, 8000)
                    }
                }
            }
            false
        }

        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (userTouching && !userScrolling) {
                userScrolling = true
                btnJumpToCurrent.visibility = View.VISIBLE
            }
        }

        btnJumpToCurrent.setOnClickListener {
            userScrolling = false
            btnJumpToCurrent.visibility = View.GONE
            handler.removeCallbacks(scrollResetRunnable)
            val state = mediaTracker.state.value
            if (state.currentIndex >= 0) {
                lastScrolledIndex = state.currentIndex
                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
            }
        }
    }

    private fun autoScrollToCurrentLine(currentIndex: Int, totalLines: Int) {
        if (totalLines == 0) return
        scrollView.post {
            val targetY = (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }

    private fun updateAaDelayDisplay() {
        val sign = if (aaOffsetMs > 0) "+" else ""
        tvAaDelay.text = "${sign}${aaOffsetMs}ms"
    }

    private fun selectFont(family: String) {
        lyricsFontFamily = family
        saveFontSettings()
        applyFontSettings()
        updateFontButtonHighlights()
    }

    private fun saveFontSettings() {
        prefs.edit()
            .putInt("lyrics_font_size", lyricsFontSizeSp)
            .putString("lyrics_font_family", lyricsFontFamily)
            .apply()
    }

    private fun applyFontSettings() {
        tvLyrics.textSize = lyricsFontSizeSp.toFloat()
        tvLyrics.typeface = Typeface.create(lyricsFontFamily, Typeface.NORMAL)
        tvFontSize.text = "${lyricsFontSizeSp}sp"
    }

    private fun updateFontButtonHighlights() {
        val selectedTint = Color.parseColor("#3A3A5E")
        val normalTint = Color.parseColor("#2A2A3E")
        fontButtons.forEach { (family, button) ->
            button.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (family == lyricsFontFamily) selectedTint else normalTint
            )
        }
    }

    private fun onTapSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a synced song first")
            return
        }

        if (!tapSyncActive) {
            tapSyncActive = true
            tapSyncOffsets.clear()
            val nextIdx = (state.currentIndex + 1).coerceAtMost(state.lines.size - 1)
            tapSyncTargetLineIndex = nextIdx
            btnTapSync.text = "TAP when you hear line ${tapSyncOffsets.size + 1}/3"
            showSyncStatus("Waiting for line: \"${state.lines.getOrNull(nextIdx)?.text?.take(30) ?: "..."}\"")
        } else {
            val targetLine = state.lines.getOrNull(tapSyncTargetLineIndex)
            if (targetLine != null && targetLine.timeMs > 0) {
                val rawPos = getCurrentRawPositionMs()
                val offset = rawPos - targetLine.timeMs
                tapSyncOffsets.add(offset)
            }

            if (tapSyncOffsets.size >= 3) {
                val avgOffset = tapSyncOffsets.average().toLong()
                mediaTracker.setOffset(avgOffset)
                showSyncStatus("Synced: ${if (avgOffset >= 0) "+" else ""}${avgOffset}ms")
                tapSyncActive = false
                btnTapSync.text = "Tap to Sync"
            } else {
                tapSyncTargetLineIndex = (tapSyncTargetLineIndex + 1).coerceAtMost(state.lines.size - 1)
                btnTapSync.text = "TAP when you hear line ${tapSyncOffsets.size + 1}/3"
                showSyncStatus("Waiting for line: \"${state.lines.getOrNull(tapSyncTargetLineIndex)?.text?.take(30) ?: "..."}\"")
            }
        }
    }

    private fun getCurrentRawPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() - mediaTracker.state.value.offsetMs
        } catch (_: Exception) { 0L }
    }

    private val hideSyncStatusRunnable = Runnable {
        if (!tapSyncActive) {
            tvSyncStatus.visibility = View.GONE
        }
    }

    private fun showSyncStatus(text: String) {
        tvSyncStatus.text = text
        tvSyncStatus.visibility = View.VISIBLE
        handler.removeCallbacks(hideSyncStatusRunnable)
        handler.postDelayed(hideSyncStatusRunnable, 8000)
    }

    private fun onAutoSyncPressed() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND || !state.isPlaying) {
            showSyncStatus("Play a synced song first")
            return
        }

        if (audioSyncHelper != null) {
            audioSyncHelper?.stop()
            audioSyncHelper = null
            btnAutoSync.text = "\uD83C\uDFA4 Auto Sync"
            showSyncStatus("Cancelled")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
            return
        }

        startAudioSync()
    }

    private fun startAudioSync() {
        val state = mediaTracker.state.value
        if (state.status != LyricsStatus.FOUND) return

        val rawPos = getCurrentRawPositionMs()
        btnAutoSync.text = "Cancel"

        audioSyncHelper = AudioSyncHelper(
            context = this,
            onResult = { offsetMs ->
                runOnUiThread {
                    mediaTracker.setOffset(offsetMs)
                    showSyncStatus("Synced: ${if (offsetMs >= 0) "+" else ""}${offsetMs}ms")
                    btnAutoSync.text = "\uD83C\uDFA4 Auto Sync"
                    audioSyncHelper = null
                }
            },
            onStatus = { msg ->
                runOnUiThread { showSyncStatus(msg) }
            },
            onError = { msg ->
                runOnUiThread {
                    showSyncStatus(msg)
                    btnAutoSync.text = "\uD83C\uDFA4 Auto Sync"
                    audioSyncHelper = null
                }
            },
            onResumePlayback = {
                mediaTracker.resumePlayback()
                val newPos = getCurrentRawPositionMs()
                audioSyncHelper?.updatePosition(newPos)
            }
        )
        audioSyncHelper?.start(state.lines, rawPos)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_MIC && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAudioSync()
        } else if (requestCode == RC_MIC) {
            showSyncStatus("Microphone permission denied")
        }
    }

    private fun startPlainScroll(state: LyricsState) {
        val durationMs = state.track?.durationMs ?: 0
        if (durationMs <= 0 || state.lines.isEmpty()) {
            scrollView.scrollTo(0, 0)
            return
        }

        val trackTitle = state.track?.title
        val isNewTrack = trackTitle != lastPlainTrackTitle
        lastPlainTrackTitle = trackTitle

        tvLyrics.post {
            val maxScroll = tvLyrics.height - scrollView.height
            if (maxScroll <= 0) return@post

            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            val remainingMs = (durationMs - posMs).coerceAtLeast(0)
            val startFraction = posMs.toFloat() / durationMs
            val startScrollY = (startFraction * maxScroll).toInt().coerceIn(0, maxScroll)

            if (isNewTrack || !userScrolling) {
                scrollView.scrollTo(0, startScrollY)
            }

            plainScrollAnimator?.cancel()
            plainScrollAnimator = ValueAnimator.ofInt(startScrollY, maxScroll).apply {
                duration = remainingMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    if (!userScrolling) {
                        scrollView.scrollTo(0, anim.animatedValue as Int)
                    }
                }
                start()
                if (!state.isPlaying) pause()
            }
        }
    }

    private fun stopPlainScroll() {
        plainScrollAnimator?.cancel()
        plainScrollAnimator = null
        lastPlainTrackTitle = null
    }

    companion object {
        private const val RC_MIC = 1001
        private val DEFAULT_BG = Color.parseColor("#121212")
        private val DEFAULT_APP_BAR = Color.parseColor("#1E1E2E")
        private val DEFAULT_DELAY_BAR = Color.parseColor("#1A1A2A")
        private val DEFAULT_DIVIDER = Color.parseColor("#2A2A3A")
        private val DEFAULT_HIGHLIGHT = Color.parseColor("#FFD54F")
        private val DEFAULT_DIM = Color.parseColor("#99FFFFFF")

        private fun setAlpha(color: Int, alpha: Float): Int {
            val a = (alpha * 255).toInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
    }
}
