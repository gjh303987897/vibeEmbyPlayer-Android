package com.vibeplayer.app.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
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
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var sessionManager: PlaybackSessionManager

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession =
        sessionManager.session
}
