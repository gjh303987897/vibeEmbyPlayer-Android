package com.vibeplayer.app.ui.iptv

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.repository.IptvRepository
import com.vibeplayer.app.data.repository.IptvStreamRepository
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.di.ApplicationScope
import com.vibeplayer.app.model.IptvChannel
import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.IptvRoute
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.rankIptvRoutes
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.HlsVariant
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.SubtitleTrack
import com.vibeplayer.app.security.PrivacyManager
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.ui.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IptvRoutesState(
    val routes: List<IptvRoute> = emptyList(),
    val currentKey: String? = null,
    val automatic: Boolean = true,
    val checking: Boolean = false
)

/** One logical channel/history session, regardless of how many routes are tried or selected. */
@HiltViewModel
class IptvPlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val iptvRepository: IptvRepository,
    private val streams: IptvStreamRepository,
    private val settings: SettingsDataStore,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    private val privacyManager: PrivacyManager,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {
    val player = playerManager.player
    fun setVideoSurface(holder: android.view.SurfaceHolder, attached: Boolean) = playerManager.setVideoSurface(holder, attached)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()
    private val _routes = MutableStateFlow(IptvRoutesState())
    val routes: StateFlow<IptvRoutesState> = _routes.asStateFlow()

    private var server: ServerConfig? = null
    private var channel: IptvChannel? = null
    private var selectionJob: Job? = null
    private var refreshJob: Job? = null
    private var reporterJob: Job? = null
    private var historyJob: Job? = null
    private val triedRoutes = mutableSetOf<String>()
    private var automaticOptions: List<IptvRoute> = emptyList()
    private var active = false
    private var started = false
    private var watchedMs = 0L
    private var lastTickMs = SystemClock.elapsedRealtime()
    private var wasPlaying = false
    private var quality = IptvQualityPreference.HIGHEST

    init {
        playerManager.beginLoading()
        viewModelScope.launch {
            settings.playerPreferences.collect { quality = it.iptvQuality }
        }
        viewModelScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                if (active && server?.privateMode == true && !unlocked) {
                    stopPlayback()
                    _routes.value = IptvRoutesState()
                    _uiState.value = PlayerUiState(error = "Service is locked")
                }
            }
        }
        viewModelScope.launch {
            playerManager.state.collect { p ->
                accountWatchTime()
                wasPlaying = started && p.isPlaying && !p.buffering
                _uiState.update {
                    it.copy(
                        title = p.title.orEmpty(), subtitle = p.subtitle.orEmpty(),
                        isPlaying = p.isPlaying, isPrepared = p.isPrepared,
                        positionMs = p.positionMs, durationMs = p.durationMs,
                        buffering = p.buffering || (active && !started && _routes.value.checking),
                        error = p.error, volume = p.volume, playbackSpeed = p.playbackSpeed,
                        audioTracks = p.audioTracks, selectedAudioTrackKey = p.selectedAudioTrackKey,
                        subtitleText = p.subtitleText, bitmapSubtitleFrame = p.bitmapSubtitleFrame,
                        subtitleTracks = p.subtitleTracks, selectedSubtitleKey = p.selectedSubtitleKey,
                        audioDecode = p.audioDecode, surfaceGeneration = p.surfaceGeneration
                    )
                }
                // Errors can try each automatic route once; pause/EOF and manual selections never do.
                if (active && started && p.error != null && !p.isPrepared && _routes.value.automatic) {
                    rankIptvRoutes(automaticOptions, quality).firstOrNull { it.key !in triedRoutes }
                        ?.let { startRoute(it, playWhenReady = playerManager.playWhenReady) }
                }
            }
        }
    }

    fun play(serverId: String, encodedUrl: String, encodedName: String) {
        stopPlayback()
        val url = Routes.decodeIptvUrl(encodedUrl)
        val name = Routes.decodeIptvName(encodedName)?.ifBlank { "IPTV Channel" } ?: "IPTV Channel"
        playerManager.beginLoading(title = name)
        if (url.isNullOrBlank()) {
            _uiState.value = PlayerUiState(title = name, error = "Invalid stream address")
            return
        }
        active = true
        server = null
        channel = null
        triedRoutes.clear()
        automaticOptions = emptyList()
        _routes.value = IptvRoutesState(checking = true)
        _uiState.value = PlayerUiState(title = name, buffering = true)
        selectionJob = viewModelScope.launch {
            try {
                server = repository.getServer(serverId)
                if (!canPlay()) return@launch locked()
                channel = iptvRepository.findChannel(serverId, url, name) ?: IptvChannel(listOf(
                    IptvChannelEntity("history", serverId, name, "", "", url, 0)
                ))
                quality = settings.playerPreferences.first().iptvQuality
                val options = streams.resolve(channel!!, server?.trustSelfSignedCertificate == true)
                currentCoroutineContext().ensureActive()
                if (!canPlay()) return@launch locked()
                automaticOptions = options
                _routes.value = IptvRoutesState(routes = options)
                rankIptvRoutes(options, quality).firstOrNull()?.let { startRoute(it, playWhenReady = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("IptvPlayer", "Channel preparation failed (${error.javaClass.simpleName})")
                _routes.update { it.copy(checking = false) }
                _uiState.update { it.copy(buffering = false, error = "IJK_SOURCE_ERROR") }
            }
        }
    }

    fun selectSource(key: String) {
        val route = _routes.value.routes.firstOrNull { it.key == key } ?: return
        if (!canPlay()) return
        triedRoutes.clear()
        _routes.update { it.copy(automatic = false) }
        startRoute(route, playerManager.playWhenReady)
    }

    fun selectAutomatic() {
        if (!canPlay()) return
        triedRoutes.clear()
        _routes.update { it.copy(automatic = true) }
        rankIptvRoutes(automaticOptions, quality).firstOrNull()
            ?.let { startRoute(it, playerManager.playWhenReady) }
    }

    /** Refresh metadata without interrupting the route that is already playing. */
    fun refreshRoutes() {
        val selectedChannel = channel ?: return
        if (!canPlay() || _routes.value.checking) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _routes.update { it.copy(checking = true) }
            try {
                val options = streams.resolve(selectedChannel, server?.trustSelfSignedCertificate == true, refresh = true)
                currentCoroutineContext().ensureActive()
                if (!canPlay()) return@launch
                automaticOptions = options
                val current = _routes.value.routes.firstOrNull { it.key == _routes.value.currentKey }
                _routes.update { it.copy(routes = if (current != null && options.none { option -> option.key == current.key })
                    options + current else options) }
            } finally {
                if (active && channel === selectedChannel) _routes.update { it.copy(checking = false) }
            }
        }
    }

    private fun canPlay() = active && (server?.privateMode != true || privacyManager.privacyMode.value)
    private fun locked() {
        active = false
        _routes.value = IptvRoutesState()
        _uiState.value = PlayerUiState(error = "Service is locked")
    }

    private fun startRoute(route: IptvRoute, playWhenReady: Boolean) {
        val c = channel ?: return
        if (!canPlay()) return
        val p = playerManager.state.value
        val ended = p.durationMs > 0 && p.positionMs >= p.durationMs - 500
        if (_routes.value.currentKey == route.key && p.error == null && !ended && (p.isPrepared || p.buffering)) {
            triedRoutes += route.key
            return
        }
        val replacing = _routes.value.currentKey != null
        if (started) flushProgress() else {
            started = true
            watchedMs = 0L
            lastTickMs = SystemClock.elapsedRealtime()
            val service = server
            queueHistory { historyRepository.recordPlayback(PlaybackSource.IPTV, service, c.streamUrl, c.name) }
            startReporter()
        }
        wasPlaying = false
        triedRoutes += route.key
        _routes.update { it.copy(currentKey = route.key) }
        playerManager.play(
            url = route.sourceUrl, title = c.name, subtitle = server?.name,
            trustSelfSignedCertificate = server?.trustSelfSignedCertificate == true,
            privatePlayback = server?.privateMode == true,
            hlsVariant = route.variantUrl?.let { HlsVariant(it, route.width, route.height, route.bitrate) },
            playWhenReady = playWhenReady, recreateSurface = replacing
        )
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun beginLongPressSpeed(speed: Float) = playerManager.beginTemporarySpeed(speed)
    fun endLongPressSpeed() = playerManager.endTemporarySpeed()
    fun setVolume(volume: Float) = playerManager.setVolume(volume)
    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)
    fun selectSubtitle(track: SubtitleTrack?) = playerManager.selectSubtitle(track)
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun onPlaybackEnded() {
        if (!started) return
        flushProgress(completed = true)
        started = false
        wasPlaying = false
        reporterJob?.cancel()
        playerManager.pause()
    }

    fun stopPlayback() {
        val ownedPlayer = active || started
        active = false
        selectionJob?.cancel()
        refreshJob?.cancel()
        reporterJob?.cancel()
        if (started) flushProgress()
        started = false
        wasPlaying = false
        if (ownedPlayer) playerManager.stop()
    }

    private fun accountWatchTime() {
        val now = SystemClock.elapsedRealtime()
        if (started && wasPlaying) watchedMs += (now - lastTickMs).coerceAtLeast(0)
        lastTickMs = now
    }

    private fun startReporter() {
        reporterJob?.cancel()
        reporterJob = viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                accountWatchTime()
                if (++ticks % 10 == 0) flushProgress()
            }
        }
    }

    private fun flushProgress(completed: Boolean = false) {
        val c = channel ?: return
        accountWatchTime()
        val service = server
        val p = playerManager.state.value
        val seconds = watchedMs / 1_000
        watchedMs %= 1_000
        // Capture before play() resets native counters; a new route's position starts from zero.
        val bytes = playerManager.drainDownloadedBytes()
        queueHistory {
            if (completed) historyRepository.completePlayback(PlaybackSource.IPTV, service, c.streamUrl, p.durationMs / 1_000)
            else historyRepository.updateProgress(PlaybackSource.IPTV, service, c.streamUrl, p.positionMs / 1_000, p.durationMs / 1_000)
            if (service != null && (seconds > 0 || bytes > 0)) historyRepository.addDailyUsage(service, seconds, bytes)
        }
    }

    /** Preserve occurrence/progress order even when navigation cancels this ViewModel. */
    private fun queueHistory(write: suspend () -> Unit) {
        val previous = historyJob
        historyJob = appScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                try { write() }
                catch (error: Exception) { Log.e("IptvPlayer", "History update failed (${error.javaClass.simpleName})") }
            }
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
