package com.vibeplayer.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.domain.link.LinkPlaybackService
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
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
 * Plays an external HTTP(S) media / HLS link and records it into the unified
 * plus dedicated link history and the daily usage statistics under the stable
 * built-in Link source.
 */
@HiltViewModel
class LinkPlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository
) : ViewModel() {

    val player = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolvedUrl: String = ""
    private var displayName: String = ""
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
                        error = p.error
                    )
                }
            }
        }
    }

    fun play(encodedUrl: String) {
        val url = Routes.decodeLinkUrl(encodedUrl)
        if (url.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Invalid playback address") }
            return
        }
        when (val resolved = LinkPlaybackService.resolvePlaybackUrl(url)) {
            is LinkPlaybackService.Result.Success -> {
                resolvedUrl = resolved.playbackUrl
                displayName = resolved.displayName
                playerManager.play(
                    url = resolved.playbackUrl,
                    title = resolved.displayName,
                    subtitle = BUILTIN_LINK_SERVER.name
                )
                viewModelScope.launch {
                    historyRepository.recordLinkPlayback(
                        url = resolved.playbackUrl,
                        displayName = resolved.displayName,
                        service = BUILTIN_LINK_SERVER
                    )
                }
                started = true
                startReporter()
            }
            is LinkPlaybackService.Result.Error -> {
                _uiState.update { it.copy(error = resolved.message) }
            }
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun onPlaybackEnded() {
        stopReporter()
        if (started) {
            val duration = playerManager.state.value.durationMs / 1000
            viewModelScope.launch {
                historyRepository.recordLinkPlayback(
                    url = resolvedUrl,
                    displayName = displayName,
                    service = BUILTIN_LINK_SERVER,
                    positionSeconds = duration,
                    durationSeconds = duration,
                    completed = true
                )
            }
            started = false
        }
        playerManager.player.pause()
    }

    fun stopPlayback() {
        stopReporter()
        if (started) {
            val pos = playerManager.state.value.positionMs / 1000
            viewModelScope.launch {
                historyRepository.recordLinkPlayback(
                    url = resolvedUrl,
                    displayName = displayName,
                    service = BUILTIN_LINK_SERVER,
                    positionSeconds = pos,
                    durationSeconds = playerManager.state.value.durationMs / 1000
                )
                historyRepository.addLinkUsage(BUILTIN_LINK_SERVER, (pos - lastUsageSeconds).coerceAtLeast(0), 0)
            }
            started = false
        }
        playerManager.player.pause()
    }

    private fun startReporter() {
        stopReporter()
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                historyRepository.recordLinkPlayback(
                    url = resolvedUrl,
                    displayName = displayName,
                    service = BUILTIN_LINK_SERVER,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
                val delta = (pos - lastUsageSeconds).coerceAtLeast(0)
                if (delta > 0) {
                    historyRepository.addLinkUsage(BUILTIN_LINK_SERVER, delta, 0)
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

    companion object {
        /** Stable built-in source identity used for Link usage statistics. */
        val BUILTIN_LINK_SERVER = ServerConfig(
            id = "builtin-link-playback",
            name = "Link",
            baseUrl = "",
            username = "",
            serviceType = ServiceType.LINK
        )
    }
}
