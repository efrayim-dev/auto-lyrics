package com.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestSession(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) {
            MediaTracker.getInstance(this).onMediaSessionChanged(null)
            return
        }

        val playing = controllers.firstOrNull { controller ->
            controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }

        val best = playing ?: controllers.first()
        MediaTracker.getInstance(this).onMediaSessionChanged(best)
    }
}
