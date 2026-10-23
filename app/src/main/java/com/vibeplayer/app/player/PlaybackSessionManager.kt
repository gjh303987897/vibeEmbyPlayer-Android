package com.vibeplayer.app.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

/** Android framework media session backed by the shared IJKPlayer instance. */
@Singleton
class PlaybackSessionManager @Inject constructor(
    @ApplicationContext context: Context,
    private val playerManager: PlayerManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session: MediaSession = MediaSession(context, "VibePlayer").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = playerManager.play()
            override fun onPause() = playerManager.pause()
            override fun onSeekTo(pos: Long) = playerManager.seekTo(pos)
            override fun onStop() = playerManager.stop()
            override fun onSkipToNext() = playerManager.queue.next()
            override fun onSkipToPrevious() = playerManager.queue.previous()
        })
        isActive = true
    }

    init {
        scope.launch {
            var previous: PlayerState? = null
            var previousQueue: PlaybackQueueState? = null
            combine(playerManager.state, playerManager.queue.state) { state, queue -> state to queue }.collect { (state, queue) ->
                val old = previous
                if (old == null || old.isPlaying != state.isPlaying || old.buffering != state.buffering ||
                    old.isPrepared != state.isPrepared || old.error != state.error ||
                    old.positionMs != state.positionMs || old.playbackSpeed != state.playbackSpeed || previousQueue != queue
                ) {
                    val status = when {
                        state.error != null -> PlaybackState.STATE_ERROR
                        state.buffering -> PlaybackState.STATE_BUFFERING
                        !state.isPrepared -> PlaybackState.STATE_NONE
                        state.isPlaying -> PlaybackState.STATE_PLAYING
                        else -> PlaybackState.STATE_PAUSED
                    }
                    var actions = if (state.isPrepared) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP else 0L
                    if (queue.hasPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    if (queue.hasNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
                    session.setPlaybackState(
                        PlaybackState.Builder()
                            .setActions(actions)
                            .setState(status, state.positionMs, state.playbackSpeed)
                            .setErrorMessage(state.error)
                            .build()
                    )
                }
                if (old == null || old.title != state.title || old.subtitle != state.subtitle ||
                    old.durationMs != state.durationMs || old.privatePlayback != state.privatePlayback
                ) {
                    session.setMetadata(
                        MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, if (state.privatePlayback) "VibePlayer" else state.title.orEmpty())
                            .putString(MediaMetadata.METADATA_KEY_ARTIST, if (state.privatePlayback) "" else state.subtitle.orEmpty())
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, if (state.privatePlayback) 0L else state.durationMs)
                            .build()
                    )
                }
                previous = state
                previousQueue = queue
            }
        }
    }

    fun release() {
        scope.cancel()
        session.isActive = false
        session.release()
    }
}
