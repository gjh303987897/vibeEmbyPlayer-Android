package com.vibeplayer.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vibeplayer.app.R
import com.vibeplayer.app.player.PlaybackSessionManager
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that keeps IJK playback alive and publishes framework media controls. */
@AndroidEntryPoint
class PlaybackService : android.app.Service() {
    @Inject lateinit var sessionManager: PlaybackSessionManager
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var privacyManager: PrivacyManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notifiedPlaying: Boolean? = null
    private var notifiedTitle: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val state = playerManager.state.value
        startForeground(NOTIFICATION_ID, buildNotification(state.title, state.isPlaying))
        notifiedPlaying = state.isPlaying
        notifiedTitle = state.title
        scope.launch {
            playerManager.state.collect { updateNotification(it.isPlaying, it.title) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sessionManager.session.isActive = true
        when (intent?.action) {
            ACTION_PLAY -> playerManager.play()
            ACTION_PAUSE -> playerManager.pause()
        }
        updateNotification(playerManager.state.value.isPlaying, playerManager.state.value.title)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        privacyManager.onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        sessionManager.session.isActive = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun updateNotification(isPlaying: Boolean, title: String?) {
        if (notifiedPlaying == isPlaying && notifiedTitle == title) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, isPlaying))
        notifiedPlaying = isPlaying
        notifiedTitle = title
    }
    private fun buildNotification(title: String? = null, isPlaying: Boolean = false): Notification {
        val action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        val actionTitle = if (isPlaying) getString(R.string.pause) else getString(R.string.play)
        val actionIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val pendingIntent = PendingIntent.getService(
            this, if (isPlaying) 1 else 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: getString(R.string.playback_notification_title))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(Notification.MediaStyle().setMediaSession(sessionManager.session.sessionToken).setShowActionsInCompactView(0))
            .addAction(actionIcon, actionTitle, pendingIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 1002
        private const val ACTION_PLAY = "com.vibeplayer.app.playback.PLAY"
        private const val ACTION_PAUSE = "com.vibeplayer.app.playback.PAUSE"

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
