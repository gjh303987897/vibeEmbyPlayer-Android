package com.vibeplayer.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.data.remote.PlaybackReport
import com.vibeplayer.app.data.remote.PlaybackTarget
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.UserSession
import com.vibeplayer.app.player.PlayerManager
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

data class PlayerUiState(
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val isPrepared: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val buffering: Boolean = false,
    val error: String? = null,
    val playbackSpeed: Float = 1f,
    val volume: Float = 1f
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The shared ExoPlayer instance used to render video. */
    val player = playerManager.player

    private var session: UserSession? = null
    private var report: PlaybackReport = PlaybackReport("", "", "")
    private var reporterJob: Job? = null
    private var started = false
    private var historyTarget: String? = null
    private var historyTitle: String = ""
    private var historySubtitle: String = ""

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
                        playbackSpeed = p.playbackSpeed,
                        volume = p.volume
                    )
                }
            }
        }
    }

    /** Fetches the stream URL and starts playback, reporting start/progress. */
    fun play(itemId: String) {
        playerManager.beginLoading()
        playerManager.clearError()
        session = activeSessionManager.activeSession.value
        val s = session ?: return
        viewModelScope.launch {
            val client = repository.clientFor(s.server.serviceType)
            val item = client.fetchItemDetails(s, itemId).getOrNull()
            val target: PlaybackTarget? = item?.let { client.fetchPlaybackUrl(s, it).getOrNull() }
            if (item == null || target == null) {
                _uiState.update { it.copy(error = "Unable to prepare playback") }
                return@launch
            }
            val startMs = (target.startSeconds * 1000).toLong()
            playerManager.play(
                url = target.url,
                title = item.name,
                subtitle = episodeSubtitle(item),
                startPositionMs = startMs
            )
            report = PlaybackReport(
                itemId = item.id,
                mediaSourceId = target.mediaSourceId,
                playSessionId = target.playSessionId,
                positionTicks = (target.startSeconds * 10_000_000).toLong()
            )
            client.reportPlaybackStart(s, report)
            started = true
            historyTarget = item.id
            historyTitle = item.name
            historySubtitle = episodeSubtitle(item)
            historyRepository.recordPlayback(
                source = PlaybackSource.ofServiceType(s.server.serviceType),
                service = s.server,
                replayTarget = item.id,
                title = item.name,
                subtitle = historySubtitle,
                positionSeconds = startMs / 1000,
                durationSeconds = 0
            )
            startReporter(client)
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)

    fun setVolume(volume: Float) = playerManager.setVolume(volume)

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
        reportAfterSeek()
    }

    fun stopPlayback() {
        stopReporter()
        if (started) {
            val s = session ?: return
            val client = repository.clientFor(s.server.serviceType)
            viewModelScope.launch {
                client.reportPlaybackStopped(
                    s,
                    report.copy(positionTicks = (playerManager.state.value.positionMs * 10_000L).coerceAtLeast(0))
                )
            }
            historyTarget?.let { target ->
                viewModelScope.launch {
                    historyRepository.updateProgress(
                        source = PlaybackSource.ofServiceType(s.server.serviceType),
                        service = s.server,
                        replayTarget = target,
                        positionSeconds = playerManager.state.value.positionMs / 1000,
                        durationSeconds = playerManager.state.value.durationMs / 1000
                    )
                }
            }
            started = false
        }
        playerManager.player.pause()
    }

    /** Called when playback naturally reaches the end. */
    fun onPlaybackEnded() {
        stopReporter()
        if (started) {
            val s = session ?: return
            val client = repository.clientFor(s.server.serviceType)
            val endTicks = (playerManager.state.value.durationMs * 10_000L).coerceAtLeast(report.positionTicks)
            viewModelScope.launch {
                client.reportPlaybackStopped(s, report.copy(positionTicks = endTicks))
            }
            historyTarget?.let { target ->
                viewModelScope.launch {
                    historyRepository.completePlayback(
                        source = PlaybackSource.ofServiceType(s.server.serviceType),
                        service = s.server,
                        replayTarget = target,
                        durationSeconds = playerManager.state.value.durationMs / 1000
                    )
                }
            }
            started = false
        }
        playerManager.player.pause()
    }

    private fun startReporter(client: com.vibeplayer.app.data.remote.MediaServerClient) {
        stopReporter()
        reporterJob = viewModelScope.launch {
            val s = session ?: return@launch
            while (isActive) {
                delay(10_000)
                val positionMs = playerManager.state.value.positionMs
                if (positionMs > 0) {
                    client.reportPlaybackProgress(
                        s,
                        report.copy(positionTicks = (positionMs * 10_000L).coerceAtLeast(0))
                    )
                    historyTarget?.let { target ->
                        historyRepository.updateProgress(
                            source = PlaybackSource.ofServiceType(s.server.serviceType),
                            service = s.server,
                            replayTarget = target,
                            positionSeconds = positionMs / 1000,
                            durationSeconds = playerManager.state.value.durationMs / 1000
                        )
                    }
                }
            }
        }
    }

    private fun stopReporter() {
        reporterJob?.cancel()
        reporterJob = null
    }

    private fun reportAfterSeek() {
        val s = session ?: return
        val client = repository.clientFor(s.server.serviceType)
        viewModelScope.launch {
            client.reportPlaybackProgress(
                s,
                report.copy(positionTicks = (playerManager.state.value.positionMs * 10_000L).coerceAtLeast(0))
            )
        }
    }

    private fun episodeSubtitle(item: com.vibeplayer.app.model.MediaItem): String {
        val season = item.parentIndexNumber
        val index = item.indexNumber
        return if (index.isNotBlank()) "S$season E$index" else item.seriesName
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
