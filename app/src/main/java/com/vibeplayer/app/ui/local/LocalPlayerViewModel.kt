package com.vibeplayer.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.vibeplayer.app.R
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.domain.local.LocalPlaybackService
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.hls.EncryptedHlsManager
import com.vibeplayer.app.player.hls.EncryptedHlsPlayback
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.ui.player.PlayerUiState
import com.vibeplayer.app.util.SafIds
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays a local media file (SAF content URI) and records it into the unified
 * history under the Local source. Local playback never reports bytes or
 * progress to a media server. Encrypted-HLS (`.m3u8s`) files are played through
 * the loopback decryption proxy.
 */
@HiltViewModel
class LocalPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
                        error = p.error,
                        audioTracks = p.audioTracks,
                        selectedAudioTrackKey = p.selectedAudioTrackKey
                    )
                }
            }
        }
    }

    fun play(encodedUri: String) {
        // Clear the previous source before the asynchronous SAF / HLS preparation.
        // Local and Emby screens share the application-scoped player instance.
        playerManager.beginLoading(title = "Local video", subtitle = "Local")
        playerManager.clearError()
        val uri = Routes.decodeLocalUrl(encodedUri)
        if (uri.isNullOrBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.player_invalid_url)) }
            return
        }
        this.contentUri = uri
        val name = Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: "Local video"
        this.recordTitle = name
        if (LocalPlaybackService.isEncryptedHlsManifest(name)) {
            playEncryptedHls(uri, name)
            return
        }
        viewModelScope.launch {
            // Pre-flight the SAF grant. A tree picked earlier can lose its persisted
            // permission (reinstall, "clear storage", provider change). If we handed
            // such a URI to ExoPlayer it would just sit in STATE_BUFFERING and the
            // screen looked frozen on "loading"; check it and say what is wrong.
            val problem = withContext(Dispatchers.IO) { readableFailure(uri) }
            if (problem != null) {
                _uiState.update { it.copy(error = problem) }
                return@launch
            }
            playerManager.play(url = uri, title = name, subtitle = "Local")
            historyRepository.recordPlayback(
                source = PlaybackSource.LOCAL,
                service = null,
                replayTarget = uri,
                title = name
            )
            started = true
            startReporter()
        }
    }

    /**
     * Null when the file can be opened, otherwise a user-facing reason why not.
     * Only opens the handle (no data is read), so this is cheap.
     */
    private fun readableFailure(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") return null
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { /* close */ }
            null
        } catch (_: FileNotFoundException) {
            context.getString(R.string.local_file_missing)
        } catch (_: SecurityException) {
            // Persisted read grant gone: re-ask the system to persist it once more
            // (works when the tree URI itself is still authorised) before giving up.
            if (restoreGrant(uri)) return null
            context.getString(R.string.local_permission_lost)
        } catch (_: IOException) {
            context.getString(R.string.local_file_unreadable)
        }
    }

    /**
     * Tries to (re)acquire the persisted read permission for the tree that owns
     * [documentUri] and verifies the document can be opened again afterwards.
     */
    private fun restoreGrant(documentUri: Uri): Boolean = runCatching {
        val segments = documentUri.pathSegments
        if (segments.size < 2 || segments[0] != "tree") return false
        val authority = documentUri.authority ?: return false
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, segments[1])
        val granted = context.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri == treeUri && it.isReadPermission } != null
        if (!granted) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        context.contentResolver.openAssetFileDescriptor(documentUri, "r")?.use { }
        true
    }.getOrDefault(false)

    private fun playEncryptedHls(documentUri: String, displayName: String) {
        val docUri = Uri.parse(documentUri)
        val treeUri = SafIds.treeUriOf(docUri)
        val docId = SafIds.documentIdOf(docUri)
        if (treeUri == null || docId == null) {
            _uiState.update { it.copy(error = context.getString(R.string.local_unsupported_location)) }
            return
        }
        viewModelScope.launch {
            hlsPlayback?.close()
            val containerLength = runCatching {
                context.contentResolver.openAssetFileDescriptor(docUri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            encryptedHlsManager.prepareLocal(treeUri, docId, containerLength).fold(
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
                    _uiState.update {
                        it.copy(error = e.message ?: context.getString(R.string.player_load_failed))
                    }
                }
            )
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)

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
