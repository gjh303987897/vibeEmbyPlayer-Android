package com.vibeplayer.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibeplayer.app.di.OkHttpClientFactory
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

data class SubtitleTrack(val key: String, val label: String, val language: String?, val selected: Boolean)

data class AudioTrack(val key: String, val label: String, val language: String?, val selected: Boolean)

data class PlayerState(
    val isPlaying: Boolean = false, val positionMs: Long = 0L, val durationMs: Long = 0L,
    val isPrepared: Boolean = false, val buffering: Boolean = false, val title: String? = null,
    val subtitle: String? = null, val error: String? = null, val playbackSpeed: Float = 1f,
    val volume: Float = 1f, val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleKey: String? = null, val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioTrackKey: String? = null
)

@OptIn(UnstableApi::class)
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    clientFactory: OkHttpClientFactory
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val headerFactory = AuthHeaderDataSourceFactory(clientFactory)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, headerFactory)))
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionTicker: Job? = null
    private var lastSpeed = 1f
    private var autoSubtitleSelected = false

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                updateTracks(tracks)
                if (!autoSubtitleSelected) {
                    subtitleTracks(tracks).firstOrNull()?.let {
                        autoSubtitleSelected = true
                        selectSubtitle(it)
                    }
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateDerived()
                when (playbackState) {
                    Player.STATE_ENDED -> { _state.update { it.copy(buffering = false) }; stopPositionTicker() }
                    Player.STATE_BUFFERING -> _state.update { it.copy(buffering = true) }
                    Player.STATE_READY -> _state.update { it.copy(buffering = false, isPrepared = true) }
                    Player.STATE_IDLE -> _state.update { it.copy(buffering = false, isPrepared = false) }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying, buffering = if (isPlaying) false else it.buffering) }
                if (isPlaying) startPositionTicker() else stopPositionTicker()
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = error.errorCodeName, buffering = false, isPrepared = false) }
                stopPositionTicker()
            }
        })
    }

    fun setPlaybackHeaders(headers: Map<String, String>) = headerFactory.configure(headers, false)
    fun clearError() { _state.update { it.copy(error = null) } }
    fun beginLoading(title: String? = null, subtitle: String? = null) {
        player.pause(); player.stop(); player.clearMediaItems()
        _state.value = PlayerState(
            title = title,
            subtitle = subtitle,
            playbackSpeed = lastSpeed,
            volume = player.volume
        )
    }
    fun play(
        url: String,
        title: String?,
        subtitle: String?,
        startPositionMs: Long = 0L,
        headers: Map<String, String> = emptyMap(),
        trustSelfSignedCertificate: Boolean = false
    ) {
        headerFactory.configure(headers, trustSelfSignedCertificate); player.stop(); player.clearMediaItems()
        autoSubtitleSelected = false
        _state.value = PlayerState(
            title = title,
            subtitle = subtitle,
            positionMs = startPositionMs,
            playbackSpeed = lastSpeed,
            volume = player.volume
        )
        // A new source must not inherit a previous subtitle-off selection.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        player.setMediaItem(PlayerMediaItem.Builder().setUri(url).setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).build()).build())
        runCatching { context.startForegroundService(android.content.Intent(context, com.vibeplayer.app.service.PlaybackService::class.java)) }
        player.prepare(); if (lastSpeed != 1f) player.setPlaybackSpeed(lastSpeed); if (startPositionMs > 0) player.seekTo(startPositionMs); player.play()
    }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun seekTo(positionMs: Long) { player.seekTo(positionMs.coerceAtLeast(0L)); updateDerived() }
    fun setPlaybackSpeed(speed: Float) { lastSpeed = speed.coerceIn(.25f, 4f); player.setPlaybackSpeed(lastSpeed); _state.update { it.copy(playbackSpeed = lastSpeed) } }
    fun setVolume(volume: Float) { val safe = volume.coerceIn(0f, 1f); player.volume = safe; _state.update { it.copy(volume = safe) } }
    fun selectSubtitle(track: SubtitleTrack?) {
        val b = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, track == null)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (track != null) {
            val p = track.key.split(":")
            val g = p.getOrNull(0)?.toIntOrNull()
            val t = p.getOrNull(1)?.toIntOrNull()
            if (g != null && t != null) {
                player.currentTracks.groups.getOrNull(g)?.let {
                    b.addOverride(TrackSelectionOverride(it.mediaTrackGroup, listOf(t)))
                }
            }
        }
        player.trackSelectionParameters = b.build(); updateTracks(player.currentTracks)
    }
    fun selectAudioTrack(track: AudioTrack) {
        val (groupIndex, trackIndex) = parseTrackKey(track.key) ?: return
        val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
            .build()
        updateTracks(player.currentTracks)
    }
    fun release() { positionTicker?.cancel(); player.release(); scope.cancel() }
    private fun startPositionTicker() { if (positionTicker != null) return; positionTicker = scope.launch { while (true) { updateDerived(); delay(500) } } }
    private fun stopPositionTicker() { updateDerived(); positionTicker?.cancel(); positionTicker = null }
    private fun updateTracks(tracks: Tracks) {
        val subtitles = subtitleTracks(tracks)
        val audio = audioTracks(tracks)
        _state.update {
            it.copy(
                subtitleTracks = subtitles,
                selectedSubtitleKey = subtitles.firstOrNull { track -> track.selected }?.key,
                audioTracks = audio,
                selectedAudioTrackKey = audio.firstOrNull { track -> track.selected }?.key
            )
        }
    }
    private fun subtitleTracks(tracks: Tracks): List<SubtitleTrack> = tracks.groups.mapIndexedNotNull { gi, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@mapIndexedNotNull null
            (0 until group.length).mapNotNull { ti ->
                val f = group.getTrackFormat(ti)
                if (!group.isTrackSupported(ti)) return@mapNotNull null
                val label = f.label?.takeIf { it.isNotBlank() } ?: f.language?.takeIf { it.isNotBlank() } ?: "Subtitle ${ti + 1}"
                SubtitleTrack("$gi:$ti", label, f.language, group.isTrackSelected(ti))
            }
    }.flatten()
    private fun audioTracks(tracks: Tracks): List<AudioTrack> = tracks.groups.mapIndexedNotNull { gi, group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
        (0 until group.length).mapNotNull { ti ->
            val format = group.getTrackFormat(ti)
            if (!group.isTrackSupported(ti)) return@mapNotNull null
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: format.language?.takeIf { it.isNotBlank() }
                ?: "Audio ${ti + 1}"
            AudioTrack("$gi:$ti", label, format.language, group.isTrackSelected(ti))
        }
    }.flatten()
    private fun updateDerived() {
        val tracks = player.currentTracks
        val subtitles = subtitleTracks(tracks)
        val audio = audioTracks(tracks)
        _state.update {
            it.copy(
                isPlaying = player.isPlaying,
                isPrepared = player.playbackState != Player.STATE_IDLE,
                positionMs = player.currentPosition,
                durationMs = player.duration.takeIf { duration -> duration > 0 } ?: it.durationMs,
                subtitleTracks = subtitles,
                selectedSubtitleKey = subtitles.firstOrNull { track -> track.selected }?.key,
                audioTracks = audio,
                selectedAudioTrackKey = audio.firstOrNull { track -> track.selected }?.key
            )
        }
    }
}

internal fun parseTrackKey(key: String): Pair<Int, Int>? {
    val parts = key.split(":")
    if (parts.size != 2) return null
    val groupIndex = parts[0].toIntOrNull() ?: return null
    val trackIndex = parts[1].toIntOrNull() ?: return null
    if (groupIndex < 0 || trackIndex < 0) return null
    return groupIndex to trackIndex
}
