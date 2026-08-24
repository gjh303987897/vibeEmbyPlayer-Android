package com.vibeplayer.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Snapshot of the current playback position / controls state. */
data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPrepared: Boolean = false,
    val buffering: Boolean = false,
    val title: String? = null,
    val subtitle: String? = null,
    val error: String? = null,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f
)

/**
 * Application-scoped ExoPlayer (Media3) wrapper. All playback — online media
 * servers, WebDAV, IPTV, local files — goes through this single manager.
 */
@OptIn(UnstableApi::class)
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val headerFactory = AuthHeaderDataSourceFactory()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                // Use DefaultDataSource so that non-HTTP schemes (local SAF
                // content://, file://, asset://, ...) are served by Media3's
                // built-in content/file data sources, while http(s) streams
                // still go through the header-injecting HTTP factory. Without
                // this wrapper the player is built with an HTTP-only factory,
                // so a local content:// URI can never be read -> the player
                // stays stuck in STATE_BUFFERING (endless "loading").
                DefaultDataSource.Factory(context, headerFactory)
            )
        )
        .build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var positionTicker: Job? = null

    private var lastSpeed: Float = 1f

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateDerived()
                when (playbackState) {
                    Player.STATE_ENDED -> stopPositionTicker()
                    Player.STATE_BUFFERING -> _state.update { it.copy(buffering = true) }
                    Player.STATE_READY -> _state.update { it.copy(buffering = false, isPrepared = true) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startPositionTicker() else stopPositionTicker()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.errorCodeName) }
            }
        })
    }

    /** Sets the request headers to inject into subsequent HTTP requests (e.g. WebDAV auth). */
    fun setPlaybackHeaders(headers: Map<String, String>) {
        headerFactory.setHeaders(headers)
    }

    /** Prepares and plays the given stream URL with optional HTTP request headers. */
    fun play(
        url: String,
        title: String?,
        subtitle: String?,
        startPositionMs: Long = 0L,
        headers: Map<String, String> = emptyMap()
    ) {
        // Always reset headers so stale auth headers from a previous source are
        // never carried over to an unrelated (or unauthenticated) stream.
        headerFactory.setHeaders(headers)
        _state.value = PlayerState(
            isPrepared = false,
            title = title,
            subtitle = subtitle,
            durationMs = 0L,
            positionMs = startPositionMs,
            playbackSpeed = lastSpeed
        )
        player.setMediaItem(
            PlayerMediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(subtitle)
                        .build()
                )
                .build()
        )
        // Start the media session service so the system notification, lock-screen
        // controls and background playback are available for this playback.
        runCatching {
            context.startForegroundService(
                android.content.Intent(context, com.vibeplayer.app.service.PlaybackService::class.java)
            )
        }
        player.prepare()
        // Re-apply the user's chosen playback speed to the new source.
        if (lastSpeed != 1f) player.setPlaybackSpeed(lastSpeed)
        // Seek after prepare so the resume position reliably applies.
        if (startPositionMs > 0) player.seekTo(startPositionMs)
        player.play()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        updateDerived()
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.25f, 4f)
        lastSpeed = safe
        player.setPlaybackSpeed(safe)
        _state.update { it.copy(playbackSpeed = safe) }
    }

    /** Sets the player volume in the [0, 1] range. */
    fun setVolume(volume: Float) {
        val safe = volume.coerceIn(0f, 1f)
        player.volume = safe
        _state.update { it.copy(volume = safe) }
    }

    fun release() {
        positionTicker?.cancel()
        player.release()
        scope.cancel()
    }

    private fun startPositionTicker() {
        if (positionTicker != null) return
        positionTicker = scope.launch {
            while (true) {
                updateDerived()
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        updateDerived()
        positionTicker?.cancel()
        positionTicker = null
    }

    private fun updateDerived() {
        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                isPrepared = player.playbackState != Player.STATE_IDLE,
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { d -> d > 0 } ?: it.durationMs
            )
        }
    }
}
