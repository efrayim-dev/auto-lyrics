package com.autolyrics.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class LyricsSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return LyricsScreen(carContext)
    }
}
