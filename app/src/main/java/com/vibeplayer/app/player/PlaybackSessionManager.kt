package com.vibeplayer.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped [MediaSession] wrapping the single shared [PlayerManager.player].
 *
 * Every in-app playback (Emby/Jellyfin/WebDAV/IPTV/Link/Local) runs on the same
 * ExoPlayer instance, so exposing that player through one MediaSession gives us:
 *
 * - a system media notification with play/pause/seek controls,
 * - lock-screen / bluetooth / assistant controls,
 * - foreground background playback through [com.vibeplayer.app.service.PlaybackService]
 *   without instantiating a second player.
 *
 * A single session is created per process. It lives as long as the process and is
 * released only when the player itself is released.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlaybackSessionManager @Inject constructor(
    @ApplicationContext context: Context,
    playerManager: PlayerManager
) {

    /** The session is exposed to [com.vibeplayer.app.service.PlaybackService]. */
    val session: MediaSession = MediaSession.Builder(context, playerManager.player).build()

    /**
     * Returns whether playback is currently ongoing, used by the foreground
     * service to decide whether to keep the app alive in the background.
     */
    fun isPlaying(): Boolean = session.player.isPlaying
}
