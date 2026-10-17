package com.vibeplayer.app.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.MainThread
import android.view.SurfaceHolder
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.remote.SubtitleConfiguration
import com.vibeplayer.app.di.OkHttpClientFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.misc.IMediaFormat
import tv.danmaku.ijk.media.player.misc.ITrackInfo

data class SubtitleTrack(val key: String, val label: String, val language: String?, val selected: Boolean, val selectionFlags: Int)
data class AudioTrack(val key: String, val label: String, val language: String?, val selected: Boolean)

data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPrepared: Boolean = false,
    val buffering: Boolean = false,
    val title: String? = null,
    val subtitle: String? = null,
    val subtitleText: String? = null,
    val bitmapSubtitleFrame: BitmapSubtitleFrame? = null,
    val error: String? = null,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleKey: String? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioTrackKey: String? = null,
    val audioDecode: AudioDecodeInfo? = null,
    val videoDecodeMode: VideoDecodeMode? = null,
    val surfaceGeneration: Long = 0L,
    val privatePlayback: Boolean = false
)

private data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

private data class PlaybackRequest(
    val url: String,
    val title: String?,
    val subtitle: String?,
    val headers: Map<String, String>,
    val trustSelfSignedCertificate: Boolean,
    val privatePlayback: Boolean,
    val subtitles: List<SubtitleConfiguration>,
    val hlsVariant: HlsVariant?
)

private data class DecoderRestore(
    val playWhenReady: Boolean,
    val audioKey: String?,
    val subtitleKey: String?,
    val restoreSubtitle: Boolean
)

@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: OkHttpClientFactory,
    private val settingsDataStore: SettingsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val volumeController = PlaybackVolumeController(context)
    private val _state = MutableStateFlow(PlayerState(volume = volumeController.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private val _preferences = MutableStateFlow(PlayerPreferences())
    val preferences: StateFlow<PlayerPreferences> = _preferences.asStateFlow()
    private val preferencesReady = CompletableDeferred<Unit>()
    private var preparationJob: Job? = null
    private var currentRequest: PlaybackRequest? = null
    private var decoderRestore: DecoderRestore? = null
    private var playWhenPrepared = true
    val playWhenReady: Boolean get() = playWhenPrepared
    private var surfaceGeneration = 0L
    private var surfaceReady: CompletableDeferred<Unit>? = null
    val player = IjkMediaPlayer()
    private var boundHolder: SurfaceHolder? = null

    fun setVideoSurface(holder: SurfaceHolder, attached: Boolean) {
        if (attached) {
            boundHolder = holder
            player.setDisplay(holder)
            val ready = surfaceReady
            surfaceReady = null
            ready?.complete(Unit)
        } else if (boundHolder === holder) {
            boundHolder = null
            player.setDisplay(null)
        }
    }

    private fun resetPlayer() {
        audioFocus.abandon()
        acceptNativeSubtitles = false
        subtitleSeekPending = false
        subtitleSelectionInitialized = false
        clearVisibleSubtitles()
        player.reset()
        // reset() creates a new native player whose revision starts again at zero.
        bitmapSubtitleRevision = -1L
        player.setDisplay(boundHolder)
    }

    private var positionTicker: Job? = null
    private var subtitleJob: Job? = null
    private var httpProxy: PlaybackHttpProxy? = null
    private var subtitleCues: List<SubtitleCue> = emptyList()
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentTrustSelfSigned = false
    private var preferredTextLanguage = ""
    private var preferredTextLanguageLoaded = false
    private var lastSpeed = 1f
    private var speedBeforeTemporary = 1f
    private var temporarySpeedActive = false
    private var lastNativeDownloadedBytes = 0L
    private val downloadedBytes = AtomicLong(0L)
    private var bitmapSubtitleRevision = -1L
    private var acceptNativeSubtitles = false
    private var subtitleSeekPending = false
    private var subtitleSelectionInitialized = false
    private val audioFocus = PlaybackAudioFocus(
        context,
        onPauseForLoss = { pauseForFocusLoss() },
        onResumeAfterGain = { play() },
        onDuckingChanged = { multiplier ->
            runCatching { player.setVolume(multiplier, multiplier) }
        }
    )

    init {
        scope.launch {
            settingsDataStore.playerPreferences.catch { error ->
                if (error is java.io.IOException) emit(PlayerPreferences()) else throw error
            }.collect { preferences ->
                val previous = _preferences.value
                val loaded = preferencesReady.isCompleted
                _preferences.value = preferences
                preferencesReady.complete(Unit)
                if (loaded && previous.videoDecodeMode != preferences.videoDecodeMode) restartForDecoderChange()
            }
        }
        scope.launch {
            settingsDataStore.preferredTextLanguage.collect {
                preferredTextLanguage = it
                preferredTextLanguageLoaded = true
                if (_state.value.isPrepared && !subtitleSelectionInitialized) selectInitialSubtitle()
            }
        }
        installListeners()
    }

    fun drainDownloadedBytes(): Long = downloadedBytes.getAndSet(0L).coerceAtLeast(0L)

    @MainThread
    private fun installListeners() {
        player.setOnPreparedListener {
            val restore = decoderRestore
            decoderRestore = null
            Log.i("PlayerManager", "Video prepared (decoder=${player.videoDecoder}, restoring=${restore != null})")
            acceptNativeSubtitles = true
            _state.update { it.copy(isPrepared = true, buffering = false, error = null) }
            updateTracks()
            if (lastSpeed != 1f) player.setSpeed(lastSpeed)
            val start = _state.value.positionMs
            if (start > 0L) seekTo(start)
            restore?.audioKey?.let { key -> _state.value.audioTracks.firstOrNull { it.key == key }?.let(::selectAudioTrack) }
            if (restore?.restoreSubtitle == true) {
                selectSubtitle(_state.value.subtitleTracks.firstOrNull { it.key == restore.subtitleKey }, persist = false)
            } else if (preferredTextLanguageLoaded) selectInitialSubtitle()
            if (restore?.playWhenReady ?: playWhenPrepared) play() else updateDerived(isPlaying = false)
        }
        player.setOnCompletionListener {
            audioFocus.abandon()
            stopPositionTicker()
            acceptNativeSubtitles = false
            clearVisibleSubtitles()
            updateDerived(isPlaying = false)
            _state.update { it.copy(buffering = false, isPrepared = true, subtitleText = null) }
        }
        player.setOnBufferingUpdateListener { _, percent ->
            _state.update { it.copy(buffering = percent < 100 && !it.isPlaying) }
        }
        player.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> _state.update { it.copy(buffering = true) }
                IMediaPlayer.MEDIA_INFO_BUFFERING_END,
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START,
                IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START -> _state.update { it.copy(buffering = false) }
            }
            if (what == IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START || what == IMediaPlayer.MEDIA_INFO_METADATA_UPDATE) {
                updateTracks()
                if (preferredTextLanguageLoaded && !subtitleSelectionInitialized) selectInitialSubtitle()
            }
            false
        }
        player.setOnErrorListener { _, what, extra ->
            val errorCode = httpProxy?.failure?.let { "PLAYBACK_HTTP_${it.name}" }
                ?: "IJK_ERROR_${what}_${extra}"
            Log.e("PlayerManager", "Playback failed (what=$what, extra=$extra, code=$errorCode)")
            stop()
            _state.update {
                it.copy(
                    error = errorCode,
                    buffering = false,
                    isPrepared = false,
                    isPlaying = false
                )
            }
            true
        }
        player.setOnSeekCompleteListener {
            subtitleSeekPending = false
            updateDerived()
            if (acceptNativeSubtitles && _state.value.selectedSubtitleKey.orEmpty().startsWith("timedtext:")) {
                readBitmapSubtitle()
            }
        }
        player.setOnTimedTextListener { _, text ->
            if (acceptNativeSubtitles && !subtitleSeekPending &&
                _state.value.selectedSubtitleKey.orEmpty().startsWith("timedtext:")) {
                readBitmapSubtitle()
                if (!acceptNativeSubtitles) return@setOnTimedTextListener
                _state.update {
                    it.copy(subtitleText = if (it.bitmapSubtitleFrame == null) text?.text?.takeIf(String::isNotEmpty) else null)
                }
            }
        }
    }

    private fun clearVisibleSubtitles() {
        _state.update { it.copy(subtitleText = null, bitmapSubtitleFrame = null) }
    }

    private fun readBitmapSubtitle() {
        val frame = readNativeBitmapSubtitle(bitmapSubtitleRevision) ?: return
        bitmapSubtitleRevision = frame.revision
        val visible = frame.takeIf { it.regions.isNotEmpty() }
        _state.update {
            it.copy(bitmapSubtitleFrame = visible, subtitleText = if (visible != null) null else it.subtitleText)
        }
    }

    private fun discardBitmapSubtitleSnapshot() {
        // Record the synchronous native clear so queued old timed-text events cannot redisplay it.
        readNativeBitmapSubtitle(-1L)?.let {
            // A new cue may already have arrived; leave it unread for its event/seek completion.
            if (it.regions.isEmpty()) bitmapSubtitleRevision = it.revision
        }
    }

    private fun readNativeBitmapSubtitle(revision: Long): BitmapSubtitleFrame? = try {
        NativeBitmapSubtitles.read(player, revision)
    } catch (error: Exception) {
        failBitmapSubtitle(error)
        null
    } catch (error: OutOfMemoryError) {
        failBitmapSubtitle(error)
        null
    }

    private fun failBitmapSubtitle(error: Throwable) {
        acceptNativeSubtitles = false
        Log.e("PlayerManager", "Bitmap subtitle snapshot failed (${error.javaClass.simpleName})")
        _state.update { it.copy(subtitleText = null, bitmapSubtitleFrame = null, error = "IJK_SUBTITLE_ERROR") }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun beginLoading(title: String? = null, subtitle: String? = null) {
        preparationJob?.cancel()
        surfaceReady?.cancel()
        surfaceReady = null
        currentRequest = null
        decoderRestore = null
        stopPositionTicker()
        subtitleJob?.cancel()
        subtitleCues = emptyList()
        runCatching { resetPlayer() }
        httpProxy?.close()
        httpProxy = null
        _state.value = PlayerState(
            title = title,
            subtitle = subtitle,
            playbackSpeed = lastSpeed,
            volume = _state.value.volume,
            surfaceGeneration = surfaceGeneration
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
        subtitleConfigurations: List<SubtitleConfiguration> = emptyList(),
        hlsVariant: HlsVariant? = null,
        playWhenReady: Boolean = true,
        recreateSurface: Boolean = false
    ) {
        preparationJob?.cancel()
        if (recreateSurface && boundHolder != null) {
            surfaceReady = CompletableDeferred()
            surfaceGeneration++
            boundHolder = null
            player.setDisplay(null)
        }
        decoderRestore = null
        playWhenPrepared = playWhenReady
        currentRequest = PlaybackRequest(url, title, subtitle, headers.toMap(), trustSelfSignedCertificate,
            privatePlayback, subtitleConfigurations, hlsVariant)
        stopPositionTicker()
        subtitleJob?.cancel()
        subtitleCues = emptyList()
        externalSubtitleCandidates = subtitleConfigurations
        currentHeaders = headers.toMap()
        currentTrustSelfSigned = trustSelfSignedCertificate
        lastNativeDownloadedBytes = 0L
        downloadedBytes.set(0L)
        runCatching { resetPlayer() }
        httpProxy?.close()
        httpProxy = null
        _state.value = PlayerState(
            title = title,
            subtitle = subtitle,
            positionMs = startPositionMs.coerceAtLeast(0L),
            buffering = true,
            playbackSpeed = lastSpeed,
            volume = _state.value.volume,
            surfaceGeneration = surfaceGeneration,
            privatePlayback = privatePlayback
        )
        preparationJob = scope.launch {
            preferencesReady.await()
            // CPU rendering connects the old BufferQueue until its Surface is destroyed.
            // Wait for the view to supply a fresh Surface before MediaCodec can configure it.
            surfaceReady?.await()
            try {
                val remoteUrl = url.toHttpUrlOrNull()
                val sourceUrl = if (remoteUrl != null && (remoteUrl.host != "127.0.0.1" || hlsVariant != null)) {
                    PlaybackHttpProxy(clientFactory.client(trustSelfSignedCertificate), remoteUrl, headers, hlsVariant)
                        .also { httpProxy = it }.playbackUrl
                } else url
                if (remoteUrl != null) {
                    // IJK's global DNS cache stores sockaddr including the port, keyed only by host.
                    // Each new loopback proxy has a new port; discard the preceding session's entry.
                    player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1L)
                }
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos",
                    if (_preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE) 1L else 0L)
                Log.i("PlayerManager", "Preparing video (mode=${_preferences.value.videoDecodeMode}, startMs=$startPositionMs)")
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "subtitle", 1L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1L)
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
                player.setScreenOnWhilePlaying(true)
                player.setVolume(audioFocus.volumeMultiplier, audioFocus.volumeMultiplier)
                _state.update { it.copy(buffering = true) }
                player.setDataSource(context, Uri.parse(sourceUrl), emptyMap())
                context.startForegroundService(android.content.Intent(context, com.vibeplayer.app.service.PlaybackService::class.java))
                player.prepareAsync()
            } catch (error: Exception) {
                Log.e("PlayerManager", "Unable to prepare playback (${error.javaClass.simpleName})")
                audioFocus.abandon()
                _state.update { it.copy(error = "IJK_SOURCE_ERROR", buffering = false) }
            }
        }
    }

    private fun restartForDecoderChange() {
        val request = currentRequest ?: return
        if (_state.value.isPrepared) updateDerived()
        val state = _state.value
        if (!state.isPrepared && !state.buffering) return
        val restore = decoderRestore ?: DecoderRestore(playWhenPrepared, state.selectedAudioTrackKey,
            state.selectedSubtitleKey, state.isPrepared)
        val resumeMs = if (!state.isPrepared || state.durationMs > 0L) state.positionMs else 0L
        Log.i("PlayerManager", "Video decode mode change (mode=${_preferences.value.videoDecodeMode}, positionMs=$resumeMs, playWhenReady=${restore.playWhenReady})")
        val carriedBytes = downloadedBytes.get()
        if (boundHolder != null) {
            surfaceReady = CompletableDeferred()
            surfaceGeneration++
            boundHolder = null
            player.setDisplay(null)
        }
        play(request.url, request.title, request.subtitle, resumeMs, request.headers,
            request.trustSelfSignedCertificate, request.privatePlayback, request.subtitles,
            request.hlsVariant, restore.playWhenReady)
        downloadedBytes.addAndGet(carriedBytes)
        decoderRestore = restore
    }

    private var externalSubtitleCandidates: List<SubtitleConfiguration> = emptyList()

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    fun play() {
        playWhenPrepared = true
        if (!_state.value.isPrepared) {
            decoderRestore = decoderRestore?.copy(playWhenReady = true)
            return
        }
        if (_state.value.isPlaying) return
        if (!audioFocus.request()) return
        try {
            acceptNativeSubtitles = true
            player.start()
            startPositionTicker()
            updateDerived()
        } catch (error: Exception) {
            audioFocus.abandon()
            _state.update { it.copy(error = "IJK_START_ERROR", isPlaying = false) }
        }
    }

    fun pause() {
        audioFocus.abandon()
        pauseForFocusLoss()
    }

    private fun pauseForFocusLoss() {
        playWhenPrepared = false
        decoderRestore = decoderRestore?.copy(playWhenReady = false)
        if (!_state.value.isPrepared) {
            // During a decoder reload the native player has no restored position yet.
            _state.update { it.copy(isPlaying = false) }
            return
        }
        runCatching { player.pause() }
        stopPositionTicker()
        updateDerived(isPlaying = false)
    }

    fun stop() {
        preparationJob?.cancel()
        surfaceReady?.cancel()
        surfaceReady = null
        currentRequest = null
        decoderRestore = null
        audioFocus.abandon()
        acceptNativeSubtitles = false
        subtitleSeekPending = false
        subtitleJob?.cancel()
        subtitleCues = emptyList()
        stopPositionTicker()
        clearVisibleSubtitles()
        runCatching { player.stop() }
        _state.update { it.copy(isPrepared = false, buffering = false) }
    }

    fun seekTo(positionMs: Long) {
        clearVisibleSubtitles()
        if (!_state.value.isPrepared) return
        subtitleSeekPending = true
        try {
            player.seekTo(positionMs.coerceAtLeast(0L))
            discardBitmapSubtitleSnapshot()
        } catch (error: Exception) {
            subtitleSeekPending = false
        }
        updateDerived()
        updateSubtitleText(positionMs)
    }

    fun beginTemporarySpeed(speed: Float) {
        if (temporarySpeedActive) return
        speedBeforeTemporary = lastSpeed
        temporarySpeedActive = true
        applySpeed(speed)
    }

    fun endTemporarySpeed() {
        if (!temporarySpeedActive) return
        temporarySpeedActive = false
        applySpeed(speedBeforeTemporary)
    }

    fun setPlaybackSpeed(speed: Float) {
        temporarySpeedActive = false
        applySpeed(speed)
    }

    private fun applySpeed(speed: Float) {
        lastSpeed = speed.coerceIn(.25f, 4f)
        runCatching { player.setSpeed(lastSpeed) }
        _state.update { it.copy(playbackSpeed = lastSpeed) }
    }

    fun setVolume(volume: Float) {
        runCatching { volumeController.setVolume(volume) }
            .onSuccess { actual -> _state.update { it.copy(volume = actual) } }
            .onFailure { Log.w("PlayerManager", "Unable to change media volume (${it.javaClass.simpleName})") }
    }

    fun selectAudioTrack(track: AudioTrack) {
        // IJK reopens even an already-selected stream, racing its existing decoder thread.
        if (_state.value.selectedAudioTrackKey == track.key) return
        val index = track.key.removePrefix("audio:").toIntOrNull() ?: return
        runCatching { player.selectTrack(index) }
        updateTracks()
    }

    fun selectSubtitle(track: SubtitleTrack?, persist: Boolean = true) {
        val key = track?.key
        if (!persist && subtitleSelectionInitialized && key == _state.value.selectedSubtitleKey) return
        subtitleJob?.cancel()
        subtitleCues = emptyList()
        clearVisibleSubtitles()
        subtitleSelectionInitialized = true
        currentSubtitleTrackIndex()?.let { runCatching { player.deselectTrack(it) } }
        _state.update {
            it.copy(
                selectedSubtitleKey = key,
                subtitleTracks = it.subtitleTracks.map { candidate -> candidate.copy(selected = candidate.key == key) }
            )
        }
        when {
            key?.startsWith("timedtext:") == true -> {
                val index = key.removePrefix("timedtext:").toIntOrNull()
                if (index != null) runCatching { player.selectTrack(index) }
            }
            key?.startsWith("external:") == true -> {
                val index = key.removePrefix("external:").toIntOrNull()
                val configuration = index?.let { externalSubtitleCandidates.getOrNull(it) }
                if (configuration != null) loadExternalSubtitle(configuration, key)
            }
        }
        if (_state.value.isPrepared) {
            acceptNativeSubtitles = true
            discardBitmapSubtitleSnapshot()
        }
        if (persist) scope.launch(Dispatchers.IO) {
            when {
                track == null -> settingsDataStore.setPreferredTextLanguage("off")
                !track.language.isNullOrBlank() -> settingsDataStore.setPreferredTextLanguage(track.language)
            }
        }
    }

    private fun selectInitialSubtitle() {
        val tracks = _state.value.subtitleTracks
        if (tracks.isEmpty()) return
        if (preferredTextLanguage.equals("off", true)) {
            selectSubtitle(null, persist = false)
            return
        }
        val preferred = preferredTextLanguage.trim().lowercase(Locale.US)
        val selected = tracks.firstOrNull { preferred.isNotEmpty() && it.language?.lowercase(Locale.US)?.let { lang -> lang == preferred || lang.startsWith("$preferred-") } == true }
            ?: tracks.firstOrNull { it.selectionFlags and SubtitleConfiguration.SELECTION_FLAG_FORCED != 0 }
            ?: tracks.firstOrNull { it.selectionFlags and SubtitleConfiguration.SELECTION_FLAG_DEFAULT != 0 }
            ?: tracks.firstOrNull { it.selected }
        selectSubtitle(selected, persist = false)
    }

    private fun updateTracks() {
        val infos = runCatching { player.trackInfo?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val selectedAudio = runCatching { player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) }.getOrDefault(-1)
        val selectedText = runCatching { player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) }.getOrDefault(-1)
        val externalKey = _state.value.selectedSubtitleKey?.takeIf { it.startsWith("external:") }
        val selectedSubtitleKey = if (subtitleSelectionInitialized) _state.value.selectedSubtitleKey
            else externalKey ?: selectedText.takeIf { it >= 0 }?.let { "timedtext:$it" }
        val audio = infos.mapIndexedNotNull { index, info ->
            if (info.trackType != ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) return@mapIndexedNotNull null
            AudioTrack("audio:$index", formatLabel(info.format, info.language), info.language, index == selectedAudio)
        }
        val timedText = infos.mapIndexedNotNull { index, info ->
            if (info.trackType != ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) return@mapIndexedNotNull null
            val disposition = info.format?.getString("disposition")?.toIntOrNull() ?: 0
            val flags = (if (disposition and 0x40 != 0) SubtitleConfiguration.SELECTION_FLAG_FORCED else 0) or
                (if (disposition and 1 != 0) SubtitleConfiguration.SELECTION_FLAG_DEFAULT else 0)
            val label = info.format?.getString("title")?.takeIf { it.isNotBlank() }
                ?: info.language?.takeIf { it.isNotBlank() } ?: "Subtitle ${index + 1}"
            SubtitleTrack("timedtext:$index", label, info.language, selectedSubtitleKey == "timedtext:$index", flags)
        }
        val external = externalSubtitleCandidates.mapIndexed { index, config ->
            SubtitleTrack("external:$index", config.label ?: config.language ?: "Subtitle ${index + 1}", config.language, selectedSubtitleKey == "external:$index", config.selectionFlags)
        }
        val allSubtitles = timedText + external
        val audioInfo = (audio.firstOrNull { it.selected } ?: audio.firstOrNull())?.let { track ->
            val info = infos.getOrNull(track.key.removePrefix("audio:").toIntOrNull() ?: -1)
            val codec = info?.format?.getString("codec_name") ?: return@let null
            val decoder = runCatching { player.mediaInfo?.mAudioDecoder }.getOrNull()
            decideAudioDecode(codec, hardwareDecoder = decoder == "MediaCodec", decoderAvailable = !decoder.isNullOrBlank())
        }
        _state.update {
            it.copy(
                audioTracks = audio,
                selectedAudioTrackKey = audio.firstOrNull { track -> track.selected }?.key,
                subtitleTracks = allSubtitles,
                selectedSubtitleKey = allSubtitles.firstOrNull { track -> track.selected }?.key,
                audioDecode = audioInfo
            )
        }
    }

    private fun formatLabel(format: IMediaFormat?, language: String?): String {
        return format?.getString("title")?.takeIf { it.isNotBlank() }
            ?: language?.takeIf { it.isNotBlank() }
            ?: format?.getString("ijk-codec-name-ui")?.takeIf { it.isNotBlank() }
            ?: "Audio"
    }

    private fun currentSubtitleTrackIndex(): Int? = _state.value.selectedSubtitleKey
        ?.removePrefix("timedtext:")?.takeIf { it != _state.value.selectedSubtitleKey }?.toIntOrNull()

    private fun loadExternalSubtitle(configuration: SubtitleConfiguration, key: String) {
        val subtitleHeaders = currentHeaders
        val trustSelfSigned = currentTrustSelfSigned
        subtitleJob = scope.launch(Dispatchers.IO) {
            val cues = runCatching {
                val request = Request.Builder().url(configuration.uri).apply {
                    subtitleHeaders.forEach { (name, value) -> header(name, value) }
                }.build()
                clientFactory.client(trustSelfSigned).newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "subtitle HTTP ${response.code}" }
                    parseSubtitle(requireNotNull(response.body).bytes(), configuration.mimeType)
                }
            }.getOrElse {
                withContext(Dispatchers.Main.immediate) {
                    if (_state.value.selectedSubtitleKey == key) _state.update { it.copy(error = "IJK_SUBTITLE_ERROR") }
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                if (_state.value.selectedSubtitleKey == key) {
                    subtitleCues = cues
                    updateSubtitleText(player.currentPosition)
                }
            }
        }
    }

    private fun updateSubtitleText(positionMs: Long) {
        if (!_state.value.selectedSubtitleKey.orEmpty().startsWith("external:")) return
        val cue = subtitleCues.firstOrNull { positionMs in it.startMs..it.endMs }
        _state.update { it.copy(subtitleText = cue?.text) }
    }

    private fun startPositionTicker() {
        if (positionTicker != null) return
        positionTicker = scope.launch {
            while (true) {
                updateDerived()
                updateSubtitleText(_state.value.positionMs)
                delay(500)
            }
        }
    }

    private fun stopPositionTicker() {
        updateDerived(isPlaying = false)
        positionTicker?.cancel()
        positionTicker = null
    }

    private fun updateDerived(isPlaying: Boolean = runCatching { player.isPlaying }.getOrDefault(false)) {
        val position = runCatching { player.currentPosition }.getOrDefault(_state.value.positionMs).coerceAtLeast(0L)
        val duration = runCatching { player.duration }.getOrDefault(_state.value.durationMs).coerceAtLeast(0L)
        val totalBytes = runCatching { player.getTrafficStatisticByteCount() }.getOrDefault(0L).coerceAtLeast(0L)
        if (totalBytes >= lastNativeDownloadedBytes) {
            downloadedBytes.addAndGet(totalBytes - lastNativeDownloadedBytes)
        }
        lastNativeDownloadedBytes = totalBytes
        val decoder = if (_state.value.isPrepared) when (player.videoDecoder) {
            IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC -> VideoDecodeMode.HARDWARE
            IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC -> VideoDecodeMode.SOFTWARE
            else -> null
        } else null
        _state.update { it.copy(isPlaying = isPlaying, positionMs = position, durationMs = duration,
            volume = volumeController.volume, videoDecodeMode = decoder) }
    }

    private fun parseSubtitle(bytes: ByteArray, mimeType: String): List<SubtitleCue> {
        if (bytes.isEmpty()) return emptyList()
        val text = bytes.toString(Charset.forName("UTF-8")).removePrefix("\uFEFF")
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split("\n\n")
        return blocks.mapNotNull { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            val timingIndex = lines.indexOfFirst { it.contains(" --> ") }
            if (timingIndex < 0) return@mapNotNull null
            val times = lines[timingIndex].split(" --> ")
            if (times.size != 2) return@mapNotNull null
            val start = parseSubtitleTime(times[0]) ?: return@mapNotNull null
            val end = parseSubtitleTime(times[1].substringBefore(' ')) ?: return@mapNotNull null
            val cueText = lines.drop(timingIndex + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "")
            SubtitleCue(start, end, cueText)
        }
    }

    private fun parseSubtitleTime(value: String): Long? {
        val normalized = value.trim().replace(',', '.')
        val parts = normalized.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        return ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong())
    }

    fun release() {
        currentRequest = null
        surfaceReady?.cancel()
        surfaceReady = null
        audioFocus.abandon()
        acceptNativeSubtitles = false
        clearVisibleSubtitles()
        stopPositionTicker()
        subtitleJob?.cancel()
        runCatching { player.release() }
        httpProxy?.close()
        httpProxy = null
        scope.cancel()
    }
}
