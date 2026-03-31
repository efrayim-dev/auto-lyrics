package com.autolyrics

import android.app.Application
import android.content.Intent
import com.autolyrics.auto.LyricsBrowserService
import com.autolyrics.media.MediaTracker

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaTracker.init(this)
        try {
            startService(Intent(this, LyricsBrowserService::class.java))
        } catch (_: Exception) { }
    }
}
