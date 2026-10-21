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
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
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
import kotlinx.coroutines.NonCancellable
import com.vibeplayer.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    private val encryptedHlsManager: EncryptedHlsManager,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    val player = playerManager.player
    fun setVideoSurface(holder: android.view.SurfaceHolder, attached: Boolean) = playerManager.setVideoSurface(holder, attached)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var contentUri: String = ""
    private var recordTitle: String = ""
    private var reporterJob: Job? = null
    private var started = false
    private var lastUsagePositionSeconds = 0L
    private var hlsPlayback: EncryptedHlsPlayback? = null
    private var loadJob: Job? = null
    private var finishJob: Job? = null

    init {
        // The player instance is shared by every source screen, so a new session
        // must drop the previous item's identity immediately - before the first
        // frame - otherwise the incoming item shows the old title, progress and
        // track list while it is still loading.
        playerManager.beginLoading()
        viewModelScope.launch {
            playerManager.state.collect { p ->
                _uiState.update {
                    it.copy(
                        title = p.title.orEmpty(),
                        subtitle = p.subtitle.orEmpty(),
                        isPlaying = p.isPlaying,
                        isPrepared = p.isPrepared,
                        positionMs = p.positionMs,
                        durationMs = p.durationMs,
                        buffering = p.buffering,
                        error = p.error,
                        audioTracks = p.audioTracks,
                        selectedAudioTrackKey = p.selectedAudioTrackKey,
                        subtitleText = p.subtitleText,
                        bitmapSubtitleFrame = p.bitmapSubtitleFrame,
                        subtitleTracks = p.subtitleTracks,
                        selectedSubtitleKey = p.selectedSubtitleKey,
                        audioDecode = p.audioDecode,
                        surfaceGeneration = p.surfaceGeneration
                    )
                }
            }
        }
    }


    fun play(encodedUri: String) {
        // Clear the previous source before the asynchronous SAF / HLS preparation.
        // Local and Emby screens share the application-scoped player instance.
        val uri = Routes.decodeLocalUrl(encodedUri)
        val name = uri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast('/') }
            ?.takeIf { it.isNotBlank() } ?: "Local video"
        playerManager.beginLoading(title = name, subtitle = "Local")
        if (uri.isNullOrBlank()) {
            _uiState.update { it.copy(error = context.getString(R.string.player_invalid_url)) }
            return
        }
        playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.LOCAL), PlaybackQueueItem(uri, name),
            onSelect = { loadItem(it.id, it.title) }, onCompleted = ::onPlaybackEnded,
            onClosed = { loadJob?.cancel(); finishCurrent(false); closeHls() })
        loadItem(uri, playerManager.queue.state.value.items[playerManager.queue.state.value.index].title)
    }

    private fun loadItem(uri: String, name: String) {
        loadJob?.cancel()
        finishCurrent(completed = false)
        closeHls()
        this.contentUri = uri
        this.recordTitle = name
        playerManager.beginLoading(title = name, subtitle = "Local", continuingQueue = true)
        loadJob = viewModelScope.launch {
            finishJob?.join()
            if (LocalPlaybackService.isEncryptedHlsManifest(name)) {
                playEncryptedHls(uri, name)
                return@launch
            }
            // Pre-flight the SAF grant. A tree picked earlier can lose its persisted
            // such a URI to IJKPlayer it would sit in buffering and the screen would look frozen;
            // check it and say what is wrong.
            val problem = withContext(Dispatchers.IO) { readableFailure(uri) }
            if (problem != null) {
                _uiState.update { it.copy(error = problem) }
                return@launch
            }
            val resumeMs = historyRepository.resumePositionSeconds(PlaybackSource.LOCAL, null, uri) * 1000
            lastUsagePositionSeconds = resumeMs / 1000
            historyRepository.recordPlayback(PlaybackSource.LOCAL, null, uri, name)
            currentCoroutineContext().ensureActive()
            started = true
            playerManager.play(url = uri, title = name, subtitle = "Local", startPositionMs = resumeMs,
                preserveSessionControls = true)
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

    private suspend fun playEncryptedHls(documentUri: String, displayName: String) {
        val docUri = Uri.parse(documentUri)
        val treeUri = SafIds.treeUriOf(docUri)
        val docId = SafIds.documentIdOf(docUri)
        if (treeUri == null || docId == null) {
            _uiState.update { it.copy(error = context.getString(R.string.local_unsupported_location)) }
            return
        }
            hlsPlayback?.close()
            val containerLength = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openAssetFileDescriptor(docUri, "r")?.use { it.length }
            }.getOrNull() ?: -1L }
            encryptedHlsManager.prepareLocal(treeUri, docId, containerLength).fold(
                onSuccess = { playback ->
                    if (!currentCoroutineContext().isActive) playback.close()
                    currentCoroutineContext().ensureActive()
                    hlsPlayback = playback
                    val title = playback.resolvedSourceName ?: displayName
                    val resumeMs = historyRepository.resumePositionSeconds(PlaybackSource.LOCAL, null, documentUri) * 1000
                    lastUsagePositionSeconds = resumeMs / 1000
                    historyRepository.recordPlayback(PlaybackSource.LOCAL, null, documentUri, title)
                    currentCoroutineContext().ensureActive()
                    started = true
                    playerManager.play(url = playback.playUrl, title = title, subtitle = "Local", startPositionMs = resumeMs,
                        preserveSessionControls = true, interruptPendingReads = playback::interruptPendingReads)
                    startReporter()
                },
                onFailure = { e ->
                    currentCoroutineContext().ensureActive()
                    _uiState.update {
                        it.copy(error = e.message ?: context.getString(R.string.player_load_failed))
                    }
                }
            )
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    /** Long-press fast-forward: speeds up until [endLongPressSpeed] restores the pick. */
    fun beginLongPressSpeed(speed: Float) = playerManager.beginTemporarySpeed(speed)

    fun endLongPressSpeed() = playerManager.endTemporarySpeed()

    /** Volume is driven by the right-edge swipe gesture on the player surface. */
    fun setVolume(volume: Float) = playerManager.setVolume(volume)

    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)

    fun selectSubtitle(track: com.vibeplayer.app.player.SubtitleTrack?) = playerManager.selectSubtitle(track)

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

    fun onPlaybackEnded() {
        finishCurrent(completed = true)
    }

    fun stopPlayback() {
        if (!playerManager.queue.owns(this)) return
        loadJob?.cancel()
        finishCurrent(completed = false)
        playerManager.queue.detach(this)
        playerManager.stop()
        closeHls()
    }

    private fun finishCurrent(completed: Boolean) {
        stopReporter()
        if (!started) return
        started = false
        val pos = playerManager.state.value.positionMs / 1000
        val dur = playerManager.state.value.durationMs / 1000
        val watchedSeconds = (pos - lastUsagePositionSeconds).coerceAtLeast(0L)
        val target = contentUri
        val bytes = playerManager.drainDownloadedBytes()
        val previous = finishJob
        finishJob = appScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                if (completed) {
                    historyRepository.completePlayback(PlaybackSource.LOCAL, null, target, dur)
                } else {
                    historyRepository.updateProgress(PlaybackSource.LOCAL, null, target, pos, dur)
                }
                historyRepository.addDailyUsage(null, watchedSeconds, bytes)
            }
        }
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
                val watchedSeconds = (pos - lastUsagePositionSeconds).coerceAtLeast(0L)
                historyRepository.updateProgress(
                    source = PlaybackSource.LOCAL,
                    service = null,
                    replayTarget = contentUri,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
                historyRepository.addDailyUsage(null, watchedSeconds, playerManager.drainDownloadedBytes())
                lastUsagePositionSeconds = pos
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
