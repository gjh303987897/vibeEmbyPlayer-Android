package com.vibeplayer.app.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.security.PrivacyManager
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.vibeplayer.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@HiltViewModel
class WebDavPlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val secureSessionStore: SecureSessionStore,
    private val webDavRepository: WebDavRepository,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    private val encryptedHlsManager: EncryptedHlsManager,
    private val privacyManager: PrivacyManager,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    val player = playerManager.player
    fun setVideoSurface(holder: android.view.SurfaceHolder, attached: Boolean) = playerManager.setVideoSurface(holder, attached)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var serverId: String? = null
    private var server: com.vibeplayer.app.model.ServerConfig? = null
    private var path: String = ""
    private var historyTitle: String = ""
    private var hlsPlayback: EncryptedHlsPlayback? = null
    private var reporterJob: Job? = null
    private var started = false
    private var lastUsagePositionSeconds = 0L
    private var loadJob: Job? = null
    private var finishJob: Job? = null

    init {
        // The player instance is shared by every source screen, so a new session
        // must drop the previous item's identity immediately - before the first
        // frame - otherwise the incoming item shows the old title, progress and
        // track list while it is still loading.
        playerManager.beginLoading()
        viewModelScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                if (!unlocked && server?.privateMode == true && playerManager.queue.owns(this@WebDavPlayerViewModel)) {
                    stopPlayback()
                    playerManager.beginLoading()
                    playerManager.failLoading("Service is locked")
                }
            }
        }
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
                        videoOutputBlocked = p.videoOutputBlocked,
                        error = p.error,
                        playbackSpeed = p.playbackSpeed,
                        volume = p.volume,
                        subtitleTracks = p.subtitleTracks,
                        selectedSubtitleKey = p.selectedSubtitleKey,
                        subtitleText = p.subtitleText,
                        bitmapSubtitleFrame = p.bitmapSubtitleFrame,
                        audioTracks = p.audioTracks,
                        selectedAudioTrackKey = p.selectedAudioTrackKey,
                        audioDecode = p.audioDecode,
                        surfaceGeneration = p.surfaceGeneration
                    )
                }
            }

        }
    }

    fun play(serverId: String, encodedPath: String) {
        val path = com.vibeplayer.app.ui.navigation.Routes.decodeWebDavPath(encodedPath)
        // Reset the shared player state before any (possibly slow) preparation:
        // this also clears the previous source's error, so neither an old
        // failure nor the old item's title can appear while this one loads.
        playerManager.beginLoading(
            title = path?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        )
        if (path.isNullOrBlank()) {
            playerManager.failLoading("Invalid server path")
            return
        }
        playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.WEBDAV, serverId),
            PlaybackQueueItem(path, path.substringAfterLast('/')),
            onSelect = { loadItem(serverId, it.id) }, onCompleted = ::onPlaybackEnded,
            onClosed = { loadJob?.cancel(); finishCurrent(false); closeHls() })
        loadItem(serverId, path)
    }

    private fun loadItem(serverId: String, path: String) {
        loadJob?.cancel()
        finishCurrent(completed = false)
        closeHls()
        this.serverId = serverId
        this.path = path
        this.historyTitle = path.substringAfterLast('/')
        playerManager.beginLoading(title = historyTitle, continuingQueue = true)
        loadJob = viewModelScope.launch {
            finishJob?.join()
            val server = repository.getServer(serverId) ?: run {
                playerManager.failLoading("Server not found")
                return@launch
            }
            if (server.privateMode && !privacyManager.privacyMode.value) {
                playerManager.queue.clear()
                playerManager.failLoading("Service is locked")
                return@launch
            }
            this@WebDavPlayerViewModel.server = server
            val password = secureSessionStore.password(serverId)
            if (password.isNullOrEmpty()) {
                playerManager.failLoading("Password not set")
                return@launch
            }
            if (isEncryptedHls(path)) {
                playEncryptedHls(server, path)
                return@launch
            }
            val url = webDavRepository.playUrl(server, path)
            lastUsagePositionSeconds = historyRepository.resumePositionSeconds(PlaybackSource.WEBDAV, server, path)
            val auth = "Basic " + Base64.getEncoder()
                .encodeToString("${server.username}:$password".toByteArray(Charsets.UTF_8))
            historyRepository.recordPlayback(PlaybackSource.WEBDAV, server, path, historyTitle, server.name)
            currentCoroutineContext().ensureActive()
            started = true
            playerManager.play(
                url = url,
                title = historyTitle,
                subtitle = server.name,
                startPositionMs = lastUsagePositionSeconds * 1000,
                headers = mapOf("Authorization" to auth),
                trustSelfSignedCertificate = server.trustSelfSignedCertificate,
                privatePlayback = server.privateMode,
                preserveSessionControls = true
            )
            startReporter(server)
        }
    }

    private suspend fun playEncryptedHls(server: com.vibeplayer.app.model.ServerConfig, webdavPath: String) {
            hlsPlayback?.close()
            encryptedHlsManager.prepareWebDav(server, webdavPath).fold(
                onSuccess = { playback ->
                    if (!currentCoroutineContext().isActive) playback.close()
                    currentCoroutineContext().ensureActive()
                    hlsPlayback = playback
                    val title = playback.resolvedSourceName ?: historyTitle
                    lastUsagePositionSeconds = historyRepository.resumePositionSeconds(PlaybackSource.WEBDAV, server, webdavPath)
                    historyRepository.recordPlayback(PlaybackSource.WEBDAV, server, webdavPath, title, server.name)
                    currentCoroutineContext().ensureActive()
                    started = true
                    playerManager.play(
                        url = playback.playUrl,
                        title = title,
                        subtitle = server.name,
                        startPositionMs = lastUsagePositionSeconds * 1000,
                        privatePlayback = server.privateMode,
                        networkByteCounter = playback.networkByteCounter,
                        interruptPendingReads = playback::interruptPendingReads,
                        preserveSessionControls = true
                    )
                    startReporter(server)
                },
                onFailure = { e ->
                    currentCoroutineContext().ensureActive()
                    playerManager.failLoading(e.message ?: "Encrypted playback failed")
                }
            )
    }

    private fun isEncryptedHls(filePath: String): Boolean =
        filePath.substringAfterLast('.').lowercase() in setOf("m3u8s", "m3u8sp")

    fun togglePlayPause() = playerManager.togglePlayPause()

    /** Long-press fast-forward: speeds up until [endLongPressSpeed] restores the pick. */
    fun beginLongPressSpeed(speed: Float) = playerManager.beginTemporarySpeed(speed)

    fun endLongPressSpeed() = playerManager.endTemporarySpeed()

    fun setVolume(volume: Float) = playerManager.setVolume(volume)

    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)

    fun selectSubtitle(track: com.vibeplayer.app.player.SubtitleTrack?) = playerManager.selectSubtitle(track)

    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)

    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)

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
        val bytes = playerManager.drainDownloadedBytes()
        val srv = server ?: return
        val target = path
        val previous = finishJob
        finishJob = appScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                if (completed) {
                    historyRepository.completePlayback(PlaybackSource.WEBDAV, srv, target, dur)
                } else {
                    historyRepository.updateProgress(PlaybackSource.WEBDAV, srv, target, pos, dur)
                }
                historyRepository.addDailyUsage(srv, watchedSeconds, bytes)
            }
        }
    }

    private fun closeHls() {
        hlsPlayback?.close()
        hlsPlayback = null
    }

    fun onPlaybackEnded() {
        finishCurrent(completed = true)
    }

    private fun startReporter(server: com.vibeplayer.app.model.ServerConfig) {
        stopReporter()
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                val watchedSeconds = (pos - lastUsagePositionSeconds).coerceAtLeast(0L)
                historyRepository.updateProgress(
                    source = PlaybackSource.WEBDAV,
                    service = server,
                    replayTarget = path,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
                historyRepository.addDailyUsage(server, watchedSeconds, playerManager.drainDownloadedBytes())
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
