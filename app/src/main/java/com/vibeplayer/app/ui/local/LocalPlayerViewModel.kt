package com.vibeplayer.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.hls.EncryptedHlsManager
import com.vibeplayer.app.player.hls.EncryptedHlsPlayback
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.ui.player.PlayerUiState
import com.vibeplayer.app.util.SafIds
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
 * Plays a local media file (SAF content URI) and records it into the unified
 * history under the Local source. Local playback never reports bytes or
 * progress to a media server. Encrypted-HLS (`.m3u8s`) files are played through
 * the loopback decryption proxy.
 */
@HiltViewModel
class LocalPlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    private val encryptedHlsManager: EncryptedHlsManager
) : ViewModel() {

    val player = playerManager.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var contentUri: String = ""
    private var recordTitle: String = ""
    private var reporterJob: Job? = null
    private var started = false
    private var hlsPlayback: EncryptedHlsPlayback? = null

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

    fun play(encodedUri: String) {
        val uri = Routes.decodeLocalUrl(encodedUri)
        if (uri.isNullOrBlank()) {
            _uiState.update { it.copy(error = "Invalid playback address") }
            return
        }
        this.contentUri = uri
        val name = Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: "Local video"
        this.recordTitle = name
        if (com.vibeplayer.app.domain.local.LocalPlaybackService.isEncryptedHlsManifest(name)) {
            playEncryptedHls(uri, name)
            return
        }
        playerManager.play(url = uri, title = name, subtitle = "Local")
        viewModelScope.launch {
            historyRepository.recordPlayback(
                source = PlaybackSource.LOCAL,
                service = null,
                replayTarget = uri,
                title = name
            )
        }
        started = true
        startReporter()
    }

    private fun playEncryptedHls(documentUri: String, displayName: String) {
        val docUri = Uri.parse(documentUri)
        val treeUri = SafIds.treeUriOf(docUri)
        val docId = SafIds.documentIdOf(docUri)
        if (treeUri == null || docId == null) {
            _uiState.update { it.copy(error = "Unsupported storage location") }
            return
        }
        viewModelScope.launch {
            hlsPlayback?.close()
            encryptedHlsManager.prepareLocal(treeUri, docId).fold(
                onSuccess = { playback ->
                    hlsPlayback = playback
                    val title = playback.resolvedSourceName ?: displayName
                    playerManager.play(url = playback.playUrl, title = title, subtitle = "Local")
                    historyRepository.recordPlayback(
                        source = PlaybackSource.LOCAL,
                        service = null,
                        replayTarget = documentUri,
                        title = title
                    )
                    started = true
                    startReporter()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message ?: "Encrypted playback failed") }
                }
            )
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun onPlaybackEnded() {
        stopReporter()
        if (started) {
            val dur = playerManager.state.value.durationMs / 1000
            viewModelScope.launch {
                historyRepository.completePlayback(
                    source = PlaybackSource.LOCAL,
                    service = null,
                    replayTarget = contentUri,
                    durationSeconds = dur
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
                historyRepository.updateProgress(
                    source = PlaybackSource.LOCAL,
                    service = null,
                    replayTarget = contentUri,
                    positionSeconds = pos,
                    durationSeconds = playerManager.state.value.durationMs / 1000
                )
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

    private fun startReporter() {
        stopReporter()
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                historyRepository.updateProgress(
                    source = PlaybackSource.LOCAL,
                    service = null,
                    replayTarget = contentUri,
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
