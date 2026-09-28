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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
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
import java.util.concurrent.atomic.AtomicLong

data class SubtitleTrack(val key: String, val label: String, val language: String?, val selected: Boolean)

data class AudioTrack(val key: String, val label: String, val language: String?, val selected: Boolean)

data class PlayerState(
    val isPlaying: Boolean = false, val positionMs: Long = 0L, val durationMs: Long = 0L,
    val isPrepared: Boolean = false, val buffering: Boolean = false, val title: String? = null,
    val subtitle: String? = null, val error: String? = null, val playbackSpeed: Float = 1f,
    val volume: Float = 1f, val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleKey: String? = null, val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioTrackKey: String? = null,
    /**
     * How the current audio track is being decoded, or null while no audio track is known.
     * Non-[AudioDecodeMode.HARDWARE] values must be surfaced to the user: a stream the phone
     * cannot decode by itself otherwise plays as silent video with no error at all.
     */
    val audioDecode: AudioDecodeInfo? = null,
    val privatePlayback: Boolean = false
)

@OptIn(UnstableApi::class)
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    clientFactory: OkHttpClientFactory,
    private val settingsDataStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val headerFactory = AuthHeaderDataSourceFactory(clientFactory)
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(DefaultDataSource.Factory(context, headerFactory)))
        .setRenderersFactory(audioRenderersFactory(context))
        .build()
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var positionTicker: Job? = null
    private var lastSpeed = 1f
    private var speedBeforeTemporary = 1f
    private var temporarySpeedActive = false
    private var subtitleSelectionApplied = false
    private var preferredTextLanguage = ""
    private var preferredTextLanguageLoaded = false
    private val downloadedBytes = AtomicLong(0L)

    fun drainDownloadedBytes(): Long = downloadedBytes.getAndSet(0L).coerceAtLeast(0L)

    init {
        scope.launch {
            settingsDataStore.preferredTextLanguage.collect {
                preferredTextLanguage = it
                preferredTextLanguageLoaded = true
                if (!subtitleSelectionApplied) selectInitialSubtitle(player.currentTracks)
            }
        }
        // Reading the MediaCodec registry and probing the FFmpeg native library is cheap but
        // not free; do it off the main thread once, before the first track callback arrives.
        scope.launch(Dispatchers.Default) { warmUpAudioDecodeSupport() }
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                updateTracks(tracks)
                if (!subtitleSelectionApplied && preferredTextLanguageLoaded) {
                    subtitleSelectionApplied = true
                    selectInitialSubtitle(tracks)
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
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                downloadedBytes.addAndGet(loadEventInfo.bytesLoaded.coerceAtLeast(0L))
            }
        })
    }

    fun setPlaybackHeaders(headers: Map<String, String>) = headerFactory.configure(headers, false)
    fun clearError() { _state.update { it.copy(error = null) } }
    /**
     * Starts a new playback session on the shared player: stops the current item
     * and replaces every per-item field of [state] with a blank slate.
     *
     * [PlayerManager] is a singleton, so its state outlives a player screen.
     * Each player ViewModel therefore calls this as soon as it is created - and
     * again with the real title before any asynchronous preparation - and mirrors
     * [state] verbatim. A screen that skipped this call would keep showing the
     * title, subtitle, progress and track list of the item the user just left.
     */
    fun beginLoading(title: String? = null, subtitle: String? = null) {
        player.pause(); player.stop(); player.clearMediaItems()
        subtitleSelectionApplied = false
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
        trustSelfSignedCertificate: Boolean = false,
        privatePlayback: Boolean = false,
        subtitleConfigurations: List<PlayerMediaItem.SubtitleConfiguration> = emptyList()
    ) {
        downloadedBytes.set(0L)
        subtitleSelectionApplied = false
        headerFactory.configure(headers, trustSelfSignedCertificate); player.stop(); player.clearMediaItems()
        _state.value = PlayerState(
            title = title,
            subtitle = subtitle,
            positionMs = startPositionMs,
            playbackSpeed = lastSpeed,
            volume = player.volume,
            privatePlayback = privatePlayback
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        player.setMediaItem(
            PlayerMediaItem.Builder()
                .setUri(url)
                .setSubtitleConfigurations(subtitleConfigurations)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(subtitle).build())
                .build()
        )
        runCatching { context.startForegroundService(android.content.Intent(context, com.vibeplayer.app.service.PlaybackService::class.java)) }
        player.prepare(); if (lastSpeed != 1f) player.setPlaybackSpeed(lastSpeed); if (startPositionMs > 0) player.seekTo(startPositionMs); player.play()
    }
    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun seekTo(positionMs: Long) { player.seekTo(positionMs.coerceAtLeast(0L)); updateDerived() }
    /**
     * Starts a temporary speed-up (long press to fast-forward). The speed the user
     * had picked is remembered so [endTemporarySpeed] can restore it; starting twice
     * is safe because only the first call captures the original speed.
     */
    fun beginTemporarySpeed(speed: Float) {
        if (temporarySpeedActive) return
        speedBeforeTemporary = lastSpeed
        temporarySpeedActive = true
        applySpeed(speed)
    }

    /** Leaves a temporary speed-up, back to the speed selected before it. */
    fun endTemporarySpeed() {
        if (!temporarySpeedActive) return
        temporarySpeedActive = false
        applySpeed(speedBeforeTemporary)
    }

    /**
     * A deliberate speed choice always wins over a long-press speed-up: picking a
     * speed while one is running ends it instead of having the release revert it.
     */
    fun setPlaybackSpeed(speed: Float) {
        temporarySpeedActive = false
        applySpeed(speed)
    }

    private fun applySpeed(speed: Float) {
        lastSpeed = speed.coerceIn(.25f, 4f)
        player.setPlaybackSpeed(lastSpeed)
        _state.update { it.copy(playbackSpeed = lastSpeed) }
    }

    fun setVolume(volume: Float) { val safe = volume.coerceIn(0f, 1f); player.volume = safe; _state.update { it.copy(volume = safe) } }
    fun selectSubtitle(track: SubtitleTrack?, persist: Boolean = true) {
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
        if (persist) scope.launch(Dispatchers.IO) {
            when {
                track == null -> settingsDataStore.setPreferredTextLanguage("off")
                !track.language.isNullOrBlank() -> settingsDataStore.setPreferredTextLanguage(track.language)
            }
        }
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
                selectedAudioTrackKey = audio.firstOrNull { track -> track.selected }?.key,
                audioDecode = audioDecodeInfo(tracks)
            )
        }
    }

    /**
     * Which decoder the selected audio track will actually go through.
     *
     * Streams are always fetched with `static=true`, so the server never transcodes and an
     * audio format the platform cannot decode is dropped by Media3 *silently* - the user
     * just gets a mute picture. [AudioDecodeInfo] is what lets the UI explain that, and what
     * tells them the bundled FFmpeg renderer is decoding the track on the device instead.
     */
    private fun audioDecodeInfo(tracks: Tracks): AudioDecodeInfo? {
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audioGroups.isEmpty()) return null
        // Prefer the track that is playing, then any decodable track, then the first one: when
        // nothing can decode it Media3 selects no audio at all and we still want the reason.
        val group = audioGroups.firstOrNull { selectedTrackIndex(it) != null }
            ?: audioGroups.firstOrNull { supportedTrackIndex(it) != null }
            ?: audioGroups.first()
        val index = selectedTrackIndex(group) ?: supportedTrackIndex(group) ?: 0
        return AudioDecodeSupport.decide(
            mimeType = group.getTrackFormat(index).sampleMimeType,
            rendererSupportsTrack = group.isTrackSupported(index)
        )
    }

    private fun selectedTrackIndex(group: Tracks.Group): Int? =
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }

    private fun supportedTrackIndex(group: Tracks.Group): Int? =
        (0 until group.length).firstOrNull { group.isTrackSupported(it) }

    private fun subtitleTracks(tracks: Tracks): List<SubtitleTrack> = tracks.groups.mapIndexedNotNull { gi, group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@mapIndexedNotNull null
            (0 until group.length).mapNotNull { ti ->
                val f = group.getTrackFormat(ti)
                if (!group.isTrackSupported(ti)) return@mapNotNull null
                val label = f.label?.takeIf { it.isNotBlank() } ?: f.language?.takeIf { it.isNotBlank() } ?: "Subtitle ${ti + 1}"
                SubtitleTrack("$gi:$ti", label, f.language, group.isTrackSelected(ti))
            }
    }.flatten()

    private fun selectInitialSubtitle(tracks: Tracks) {
        val preferred = preferredTextLanguage.trim().lowercase()
        if (preferred == "off") return
        val candidates = tracks.groups.flatMapIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@flatMapIndexed emptyList<Triple<String, androidx.media3.common.Format, Tracks.Group>>()
            (0 until group.length).mapNotNull { ti ->
                if (!group.isTrackSupported(ti)) return@mapNotNull null
                val format = group.getTrackFormat(ti)
                Triple("$gi:$ti", format, group)
            }
        }
        val selected = candidates.firstOrNull {
            preferred.isNotEmpty() && it.second.language?.lowercase()?.let { language ->
                language == preferred || language.startsWith("$preferred-")
            } == true
        }
            ?: candidates.firstOrNull { it.second.selectionFlags and C.SELECTION_FLAG_FORCED != 0 }
            ?: candidates.firstOrNull { it.second.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
        if (selected != null) {
            selectSubtitle(SubtitleTrack(selected.first, selected.second.label.orEmpty(), selected.second.language, false), persist = false)
        }
    }
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
                selectedAudioTrackKey = audio.firstOrNull { track -> track.selected }?.key,
                audioDecode = audioDecodeInfo(tracks)
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

/**
 * Renderer chain for every playback path in the app (all five player screens share one
 * [ExoPlayer], so this is the single place where decoding is decided).
 *
 * [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON] tells Media3 to instantiate the
 * optional extension renderers it finds on the classpath and to place them *behind* the
 * platform's MediaCodec renderer. Hardware therefore still does everything it is capable
 * of, and only what it cannot decode - AC-3 / E-AC-3 / DTS / DTS-HD / TrueHD on devices
 * without the corresponding licence - falls through to the on-device FFmpeg decoder from
 * `:media3-decoder-ffmpeg`. [DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER] would
 * route all audio through FFmpeg and needlessly burn battery.
 *
 * This is what removes the "picture but no sound" failure for those formats while streams
 * stay `static=true` direct copies: nothing is transcoded server-side, and servers without
 * FFmpeg work just as well. If the extension's native library is not packaged,
 * `FfmpegLibrary.isAvailable()` returns false, the renderer claims no format, and behaviour
 * is exactly the platform default - the player then says so instead of failing silently.
 */
@OptIn(UnstableApi::class)
private fun audioRenderersFactory(context: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(context)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
