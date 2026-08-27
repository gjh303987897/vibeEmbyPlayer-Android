package com.vibeplayer.app.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.hls.EncryptedHlsManager
import com.vibeplayer.app.player.hls.EncryptedHlsPlayback
import com.vibeplayer.app.ui.player.PlayerUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class WebDavPlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val secureSessionStore: SecureSessionStore,
    private val webDavRepository: WebDavRepository,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    private val encryptedHlsManager: EncryptedHlsManager
) : ViewModel() {

    val player = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var serverId: String? = null
    private var server: com.vibeplayer.app.model.ServerConfig? = null
    private var path: String = ""
    private var historyTitle: String = ""
    private var hlsPlayback: EncryptedHlsPlayback? = null
    private var reporterJob: Job? = null
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

    fun play(serverId: String, encodedPath: String) {
        // A new attempt must never inherit the previous source's failure;
        // otherwise the status overlay shows an error before anything was tried.
        playerManager.clearError()
        this.serverId = serverId
        val path = com.vibeplayer.app.ui.navigation.Routes.decodeWebDavPath(encodedPath)
        if (path.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Invalid server path") }
            return
        }
        this.path = path
        this.historyTitle = path.substringAfterLast('/')
        viewModelScope.launch {
            val server = repository.getServer(serverId) ?: run {
                _uiState.update { it.copy(error = "Server not found") }
                return@launch
            }
            this@WebDavPlayerViewModel.server = server
            val password = secureSessionStore.password(serverId)
            if (password.isNullOrEmpty()) {
                _uiState.update { it.copy(error = "Password not set") }
                return@launch
            }
            if (isEncryptedHls(path)) {
                playEncryptedHls(server, path)
                return@launch
            }
            val url = webDavRepository.playUrl(server, path)
            val auth = "Basic " + Base64.getEncoder()
                .encodeToString("${server.username}:$password".toByteArray(Charsets.UTF_8))
            playerManager.play(
                url = url,
                title = historyTitle,
                subtitle = server.name,
                headers = mapOf("Authorization" to auth)
            )
            historyRepository.recordPlayback(
                source = PlaybackSource.WEBDAV,
                service = server,
                replayTarget = path,
                title = historyTitle,
                subtitle = server.name,
                positionSeconds = 0,
                durationSeconds = 0
            )
            started = true
            startReporter(server)
        }
    }

    private fun playEncryptedHls(server: com.vibeplayer.app.model.ServerConfig, webdavPath: String) {
        viewModelScope.launch {
            hlsPlayback?.close()
            encryptedHlsManager.prepareWebDav(server, webdavPath).fold(
                onSuccess = { playback ->
                    hlsPlayback = playback
                    val title = playback.resolvedSourceName ?: historyTitle
                    playerManager.play(
                        url = playback.playUrl,
                        title = title,
                        subtitle = server.name
                    )
                    historyRepository.recordPlayback(
                        source = PlaybackSource.WEBDAV,
                        service = server,
                        replayTarget = webdavPath,
                        title = title,
                        subtitle = server.name,
                        positionSeconds = 0,
                        durationSeconds = 0
                    )
                    started = true
                    startReporter(server)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message ?: "Encrypted playback failed") }
                }
            )
        }
    }

    private fun isEncryptedHls(filePath: String): Boolean =
        filePath.substringAfterLast('.').lowercase() == "m3u8s"

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun stopPlayback() {
        stopReporter()
        if (started) {
            val pos = playerManager.state.value.positionMs / 1000
            val dur = playerManager.state.value.durationMs / 1000
            val srv = server
            if (srv != null) {
                viewModelScope.launch {
                    historyRepository.updateProgress(
                        source = PlaybackSource.WEBDAV,
                        service = srv,
                        replayTarget = path,
                        positionSeconds = pos,
                        durationSeconds = dur
                    )
                }
            }
            started = false
        }
        playerManager.player.pause()
        closeHls()
    }

    private fun closeHls() {
        hlsPlayback?.close()
        hlsPlayback = null
    }

    fun onPlaybackEnded() {
        stopReporter()
        if (started) {
            val srv = server
            if (srv != null) {
                viewModelScope.launch {
                    historyRepository.completePlayback(
                        source = PlaybackSource.WEBDAV,
                        service = srv,
                        replayTarget = path,
                        durationSeconds = playerManager.state.value.durationMs / 1000
                    )
                }
            }
            started = false
        }
        playerManager.player.pause()
    }

    private fun startReporter(server: com.vibeplayer.app.model.ServerConfig) {
        stopReporter()
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                historyRepository.updateProgress(
                    source = PlaybackSource.WEBDAV,
                    service = server,
                    replayTarget = path,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
            }
        }
    }

    private fun stopReporter() {
        reporterJob?.cancel()
        reporterJob = null
    }

    override fun onCleared() {
        stopPlayback()
        closeHls()
        super.onCleared()
    }
}
