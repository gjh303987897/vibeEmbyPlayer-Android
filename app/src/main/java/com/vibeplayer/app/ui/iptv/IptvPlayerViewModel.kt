package com.vibeplayer.app.ui.iptv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.ui.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays an IPTV channel stream and records it into the unified history plus
 * daily usage under the IPTV service. IPTV playback does not report progress to
 * any media server.
 */
@HiltViewModel
class IptvPlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository
) : ViewModel() {

    val player = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var server: ServerConfig? = null
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var reporterJob: Job? = null
    private var lastUsageSeconds: Long = 0L
    private var started = false

    init {
        viewModelScope.launch {
            playerManager.state.collect { p ->
                _uiState.update {
                    it.copy(
                        title = p.title ?: it.title,
                        subtitle = p.subtitle ?: it.subtitle,
                        isPlaying = p.isPlaying,
                        isPrepared = p.isPrepared,
                        positionMs = p.positionMs,
                        durationMs = p.durationMs,
                        buffering = p.buffering,
                        error = p.error,
                        audioTracks = p.audioTracks,
                        selectedAudioTrackKey = p.selectedAudioTrackKey
                    )
                }
            }
        }
    }

    fun play(serverId: String, encodedUrl: String, encodedName: String) {
        // A new attempt must never inherit the previous source's failure;
        // otherwise the status overlay shows an error before anything was tried.
        playerManager.clearError()
        val decodedUrl = Routes.decodeIptvUrl(encodedUrl)
        if (decodedUrl.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Invalid stream address") }
            return
        }
        val name = Routes.decodeIptvName(encodedName)?.ifBlank { "IPTV Channel" } ?: "IPTV Channel"
        val url = decodedUrl
        this.streamUrl = url
        this.channelName = name
        viewModelScope.launch {
            val s = repository.getServer(serverId)
            server = s
            playerManager.play(url = url, title = name, subtitle = s?.name)
            if (s != null) {
                lastUsageSeconds = 0L
                historyRepository.recordPlayback(
                    source = PlaybackSource.IPTV,
                    service = s,
                    replayTarget = url,
                    title = name
                )
            }
            started = true
            startReporter(s)
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun onPlaybackEnded() {
        stopReporter()
        if (started) {
            val dur = playerManager.state.value.durationMs / 1000
            val s = server
            if (s != null) {
                viewModelScope.launch {
                    historyRepository.completePlayback(
                        source = PlaybackSource.IPTV,
                        service = s,
                        replayTarget = streamUrl,
                        durationSeconds = dur
                    )
                }
            }
            started = false
        }
        playerManager.player.pause()
    }

    fun stopPlayback() {
        stopReporter()
        if (started) {
            val pos = playerManager.state.value.positionMs / 1000
            val s = server
            if (s != null) {
                viewModelScope.launch {
                    historyRepository.updateProgress(
                        source = PlaybackSource.IPTV,
                        service = s,
                        replayTarget = streamUrl,
                        positionSeconds = pos,
                        durationSeconds = playerManager.state.value.durationMs / 1000
                    )
                    historyRepository.addDailyUsage(s, (pos - lastUsageSeconds).coerceAtLeast(0), 0)
                }
            }
            started = false
        }
        playerManager.player.pause()
    }

    private fun startReporter(server: ServerConfig?) {
        stopReporter()
        lastUsageSeconds = 0L
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (server == null) continue
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                historyRepository.updateProgress(
                    source = PlaybackSource.IPTV,
                    service = server,
                    replayTarget = streamUrl,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
                val delta = (pos - lastUsageSeconds).coerceAtLeast(0)
                if (delta > 0) {
                    historyRepository.addDailyUsage(server, delta, 0)
                    lastUsageSeconds = pos
                }
            }
        }
    }

    private fun stopReporter() {
        reporterJob?.cancel()
        reporterJob = null
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
