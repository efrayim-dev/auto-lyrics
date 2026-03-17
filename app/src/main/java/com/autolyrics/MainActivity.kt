package com.autolyrics

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var mediaTracker: MediaTracker
    private lateinit var tvStatus: TextView
    private lateinit var btnPermission: Button
    private lateinit var tvTrack: TextView
    private lateinit var tvLyrics: TextView
    private lateinit var scrollView: ScrollView
    private var lastScrolledIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaTracker = MediaTracker.getInstance(this)

        tvStatus = findViewById(R.id.tv_status)
        btnPermission = findViewById(R.id.btn_permission)
        tvTrack = findViewById(R.id.tv_track)
        tvLyrics = findViewById(R.id.tv_lyrics)
        scrollView = findViewById(R.id.scroll_lyrics)

        btnPermission.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaTracker.state.collect { state ->
                    updatePermissionUi()

                    if (state.track != null) {
                        val artistText = if (state.track.artist.isNotBlank())
                            state.track.artist else "Unknown Artist"
                        tvTrack.text = "${state.track.title}\n$artistText"
                        tvTrack.visibility = View.VISIBLE
                    } else {
                        tvTrack.text = ""
                        tvTrack.visibility = View.GONE
                    }

                    when (state.status) {
                        LyricsStatus.NO_MEDIA -> {
                            tvLyrics.text = "Play a song to see lyrics here.\n\nLyrics will also appear on Android Auto."
                        }
                        LyricsStatus.LOADING -> {
                            tvLyrics.text = "Loading lyrics…"
                        }
                        LyricsStatus.NOT_FOUND -> {
                            tvLyrics.text = "No synced lyrics found for this track."
                        }
                        LyricsStatus.ERROR -> {
                            tvLyrics.text = "Error loading lyrics.\nCheck your internet connection."
                        }
                        LyricsStatus.FOUND -> {
                            val sb = StringBuilder()
                            state.lines.forEachIndexed { i, line ->
                                if (i == state.currentIndex) {
                                    sb.append("▶  ${line.text}\n\n")
                                } else {
                                    sb.append("    ${line.text}\n\n")
                                }
                            }
                            tvLyrics.text = sb.toString()

                            if (state.currentIndex != lastScrolledIndex && state.currentIndex >= 0) {
                                lastScrolledIndex = state.currentIndex
                                autoScrollToCurrentLine(state.currentIndex, state.lines.size)
                            }
                        }
                    }
                }
            }
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
            val lineHeight = tvLyrics.lineHeight
            val targetY = (currentIndex.toFloat() / totalLines * tvLyrics.height).toInt()
            val offset = scrollView.height / 3
            scrollView.smoothScrollTo(0, maxOf(0, targetY - offset))
        }
    }
}
