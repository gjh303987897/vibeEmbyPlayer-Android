package com.vibeplayer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.vibeplayer.app.R
import com.vibeplayer.app.player.PlaybackSessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Media3 [MediaSessionService] enabling system-level media controls and
 * background playback.
 *
 * The service is intentionally thin: it returns the application-scoped
 * [PlaybackSessionManager] session (which wraps the single shared ExoPlayer).
 * The Media3 stack then automatically publishes a media notification with
 * play/pause/seek controls and keeps playback alive while the app is in the
 * background or the screen is locked.
 *
 * Crash fix: a service started via `startForegroundService()` must call
 * `startForeground()` within the system time limit or the platform throws
 * `ForegroundServiceDidNotStartInTimeException` and kills the process. Media3's
 * internal scheduling can miss that window when playback starts and the app is
 * moving to the background, so we proactively promote the service to foreground
 * (with a minimal placeholder notification) in [onCreate]. Media3 then replaces
 * the placeholder with the real transport/media notification as soon as the
 * session produces one.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var sessionManager: PlaybackSessionManager

    override fun onCreate() {
        createChannel(this)
        // Promote to foreground as early as possible to satisfy the
        // startForegroundService() contract and avoid the foreground-timeout
        // crash. This is a placeholder; Media3 will refresh it with real
        // play/pause/seek controls once playback is active.
        startForeground(NOTIFICATION_ID, buildPlaceholderNotification())
        super.onCreate()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        sessionManager.session

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.playback_notification_title))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1002

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
