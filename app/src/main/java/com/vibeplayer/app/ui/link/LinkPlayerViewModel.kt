package com.vibeplayer.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.domain.link.LinkPlaybackService
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import com.vibeplayer.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope

/**
 * Plays an external HTTP(S) media / HLS link and records it into the unified
 * plus dedicated link history and the daily usage statistics under the stable
 * built-in Link source.
 */
@HiltViewModel
class LinkPlayerViewModel @Inject constructor(
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    private val repository: com.vibeplayer.app.data.repository.MediaServerRepository,
    private val privacyManager: com.vibeplayer.app.security.PrivacyManager,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    val player = playerManager.player
    fun setVideoSurface(holder: android.view.SurfaceHolder, attached: Boolean) = playerManager.setVideoSurface(holder, attached)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var resolvedUrl: String = ""
    private var displayName: String = ""
    private var reporterJob: Job? = null
    private var lastUsageSeconds: Long = 0L
    private var started = false
    private var loadJob: Job? = null
    private var finishJob: Job? = null
    private var server: ServerConfig? = null

    init {
        // The player instance is shared by every source screen, so a new session
        // must drop the previous item's identity immediately - before the first
        // frame - otherwise the incoming item shows the old title, progress and
        // track list while it is still loading.
        playerManager.beginLoading()
        viewModelScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                if (!unlocked && server?.privateMode == true && playerManager.queue.owns(this@LinkPlayerViewModel)) {
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

    fun play(encodedUrl: String, serverId: String = Routes.LEGACY_LINK_SERVICE_ID) {
        val url = Routes.decodeLinkUrl(encodedUrl)
        // Reset the shared player (and its error) before validating the link, so
        // the loading UI can never show the previously played item.
        if (url.isNullOrBlank()) {
            playerManager.beginLoading()
            playerManager.failLoading("Invalid playback address")
            return
        }
        when (val resolved = LinkPlaybackService.resolvePlaybackUrl(url)) {
            is LinkPlaybackService.Result.Success -> {
                playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.LINK, serverId),
                    PlaybackQueueItem(resolved.playbackUrl, resolved.displayName),
                    onSelect = { play(Routes.linkPlayer(it.id, serverId).substringAfterLast('/'), serverId) }, onCompleted = ::onPlaybackEnded,
                    onClosed = { loadJob?.cancel(); finishCurrent(false) })
                loadJob?.cancel()
                finishCurrent(false)
                resolvedUrl = resolved.playbackUrl
                displayName = resolved.displayName
                playerManager.beginLoading(continuingQueue = true)
                lastUsageSeconds = 0L
                loadJob = viewModelScope.launch {
                    finishJob?.join()
                    val service = if (serverId == Routes.LEGACY_LINK_SERVICE_ID) BUILTIN_LINK_SERVER
                        else repository.getServer(serverId)?.takeIf { it.serviceType == ServiceType.LINK }
                    if (service == null) {
                        playerManager.failLoading("Server not found")
                        return@launch
                    }
                    server = service
                    if (service.privateMode && !privacyManager.privacyMode.value) {
                        playerManager.queue.clear()
                        playerManager.failLoading("Service is locked")
                        return@launch
                    }
                    val resumeMs = historyRepository.resumePositionSeconds(
                        PlaybackSource.LINK, service, resolved.playbackUrl
                    ) * 1000
                    lastUsageSeconds = resumeMs / 1000
                    historyRepository.recordLinkPlayback(url = resolved.playbackUrl,
                        displayName = resolved.displayName, service = service)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (service.privateMode && !privacyManager.privacyMode.value) return@launch
                    started = true
                    playerManager.play(
                    url = resolved.playbackUrl,
                    title = resolved.displayName,
                    subtitle = service.name,
                    startPositionMs = resumeMs,
                    privatePlayback = service.privateMode
                    )
                    startReporter()
                }
            }
            is LinkPlaybackService.Result.Error -> {
                playerManager.beginLoading()
                playerManager.failLoading(resolved.message)
            }
        }
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
        finishCurrent(true)
    }

    fun stopPlayback() {
        loadJob?.cancel()
        if (!playerManager.queue.owns(this)) return
        finishCurrent(false)
        playerManager.queue.detach(this)
        playerManager.stop()
    }

    private fun finishCurrent(completed: Boolean) {
        stopReporter()
        val pos = playerManager.state.value.positionMs / 1000
        val dur = playerManager.state.value.durationMs / 1000
        val watchedSeconds = (pos - lastUsageSeconds).coerceAtLeast(0L)
        if (started) {
            val service = server
            val url = resolvedUrl
            val title = displayName
            val bytes = playerManager.drainDownloadedBytes()
            started = false
            val previous = finishJob
            finishJob = appScope.launch { withContext(NonCancellable) {
                previous?.join()
                historyRepository.recordLinkPlayback(
                    url = url,
                    displayName = title,
                    service = service,
                    positionSeconds = if (completed) dur else pos,
                    durationSeconds = dur,
                    completed = completed
                )
                historyRepository.addLinkUsage(service, watchedSeconds, bytes)
            } }
        }
    }

    private fun startReporter() {
        stopReporter()
        reporterJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                val pos = playerManager.state.value.positionMs / 1000
                val dur = playerManager.state.value.durationMs / 1000
                val watchedSeconds = (pos - lastUsageSeconds).coerceAtLeast(0L)
                historyRepository.recordLinkPlayback(
                    url = resolvedUrl,
                    displayName = displayName,
                    service = server,
                    positionSeconds = pos,
                    durationSeconds = dur
                )
                val delta = (pos - lastUsageSeconds).coerceAtLeast(0)
                if (delta > 0) {
                    historyRepository.addLinkUsage(server, watchedSeconds, playerManager.drainDownloadedBytes())
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
            id = Routes.LEGACY_LINK_SERVICE_ID,
            name = "Link",
            baseUrl = "",
            username = "",
            serviceType = ServiceType.LINK,
            privateMode = true
        )
    }
}
