package com.autolyrics

import android.app.Application
import com.autolyrics.media.MediaTracker

class AutoLyricsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaTracker.init(this)
    }
}
