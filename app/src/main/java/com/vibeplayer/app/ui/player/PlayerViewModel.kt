package com.vibeplayer.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.di.ApplicationScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.data.remote.PlaybackReport
import com.vibeplayer.app.data.remote.PlaybackTarget
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.UserSession
import com.vibeplayer.app.player.AudioDecodeInfo
import com.vibeplayer.app.player.AudioTrack
import com.vibeplayer.app.player.BitmapSubtitleFrame
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
import com.vibeplayer.app.player.SubtitleTrack
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    val volume: Float = 1f,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val selectedSubtitleKey: String? = null,
    val subtitleText: String? = null,
    val bitmapSubtitleFrame: BitmapSubtitleFrame? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val selectedAudioTrackKey: String? = null,
    /**
     * Decoder actually used for the current audio track. Shared by every player screen, which
     * all render it with `AudioDecodeNotice` - see [AudioDecodeInfo] for the meanings.
     */
    val audioDecode: AudioDecodeInfo? = null,
    val surfaceGeneration: Long = 0L
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager,
    private val playerManager: PlayerManager,
    private val historyRepository: PlaybackHistoryRepository,
    @ApplicationScope private val appScope: CoroutineScope,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** The shared IJKPlayer instance used to render video. */
    val player = playerManager.player
    fun setVideoSurface(holder: android.view.SurfaceHolder, attached: Boolean) = playerManager.setVideoSurface(holder, attached)

    private var session: UserSession? = null
    private var report: PlaybackReport = PlaybackReport("", "", "")
    private var reporterJob: Job? = null
    private var started = false
    private var historyTarget: String? = null
    private var historyTitle: String = ""
    private var historySubtitle: String = ""
    private var lastUsagePositionSeconds: Long = 0L
    private var loadJob: Job? = null
    private var queueJob: Job? = null
    private var finishJob: Job? = null

    init {
        // The player instance is shared by every source screen, so a new session
        // must drop the previous item's identity immediately - before the first
        // frame - otherwise the incoming item shows the old title, progress and
        // track list while it is still loading.
        playerManager.beginLoading()
        viewModelScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                if (!unlocked && session?.server?.privateMode == true && playerManager.queue.owns(this@PlayerViewModel)) {
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

    /** Fetches the stream URL and starts playback, reporting start/progress. */
    fun play(serverId: String, itemId: String) {
        loadItem(serverId, itemId, initial = true)
    }

    private fun loadItem(serverId: String, itemId: String, initial: Boolean = false) {
        loadJob?.cancel()
        finishCurrent(completed = false)
        // beginLoading also clears any failure from the previous attempt, so the
        // status overlay cannot show an old error before anything was tried.
        playerManager.beginLoading(continuingQueue = !initial)
        if (initial) playerManager.queue.attach(this, PlaybackQueueScope(PlaybackSource.UNKNOWN, serverId),
            PlaybackQueueItem(itemId, itemId), onSelect = { loadItem(serverId, it.id) },
            onCompleted = ::onPlaybackEnded,
            onClosed = { loadJob?.cancel(); queueJob?.cancel(); finishCurrent(false) })
        loadJob = viewModelScope.launch {
            finishJob?.join()
            // The history page can open a different service after process restart,
            // when there is no active in-memory session to inherit.
            val s = activeSessionManager.activeSession.value
                ?.takeIf { it.server.id == serverId }
                ?: withContext(Dispatchers.IO) {
                    repository.getServer(serverId)?.let(repository::restoreSession)
                }
            if (s == null) {
                playerManager.failLoading("Sign in to this service to play the item")
                return@launch
            }
            if (s.server.serviceType != ServiceType.EMBY && s.server.serviceType != ServiceType.JELLYFIN) {
                playerManager.failLoading("This service cannot play this item")
                return@launch
            }
            if (s.server.privateMode && !privacyManager.privacyMode.value) {
                playerManager.failLoading("Service is locked")
                return@launch
            }
            session = s
            val client = repository.clientFor(s.server.serviceType)
            val item = client.fetchItemDetails(s, itemId).getOrNull()
            val target: PlaybackTarget? = item?.let { client.fetchPlaybackUrl(s, it).getOrNull() }
            currentCoroutineContext().ensureActive()
            if (item == null || target == null) {
                playerManager.failLoading("Unable to prepare playback")
                return@launch
            }
            if (initial) {
                playerManager.queue.update(this@PlayerViewModel,
                    listOf(PlaybackQueueItem(item.id, item.name, episodeSubtitle(item))))
                queueJob?.cancel()
                if (item.itemType.equals("Episode", true) && item.seriesId.isNotBlank() && item.parentId.isNotBlank()) {
                    queueJob = viewModelScope.launch {
                        client.fetchSeasonEpisodes(s, item.seriesId, item.parentId).getOrNull()?.let { episodes ->
                            currentCoroutineContext().ensureActive()
                            playerManager.queue.update(this@PlayerViewModel, episodes
                                .sortedWith(compareBy({ it.indexNumber.toIntOrNull() ?: Int.MAX_VALUE }, { it.name }))
                                .map { PlaybackQueueItem(it.id, it.name, episodeSubtitle(it)) })
                        }
                    }
                }
            }
            val startMs = (target.startSeconds * 1000).toLong()
            report = PlaybackReport(item.id, target.mediaSourceId, target.playSessionId,
                (target.startSeconds * 10_000_000).toLong())
            historyTarget = item.id
            historyTitle = item.name
            historySubtitle = episodeSubtitle(item)
            lastUsagePositionSeconds = startMs / 1000
            historyRepository.recordPlayback(
                source = PlaybackSource.ofServiceType(s.server.serviceType), service = s.server,
                replayTarget = item.id, title = item.name, subtitle = historySubtitle,
                positionSeconds = startMs / 1000, durationSeconds = 0)
            client.reportPlaybackStart(s, report)
            currentCoroutineContext().ensureActive()
            started = true
            playerManager.play(
                url = target.url,
                title = item.name,
                subtitle = episodeSubtitle(item),
                startPositionMs = startMs,
                headers = mapOf("X-Emby-Token" to s.accessToken),
                trustSelfSignedCertificate = s.server.trustSelfSignedCertificate,
                privatePlayback = s.server.privateMode,
                subtitleConfigurations = target.subtitleConfigurations,
                preserveSessionControls = true
            )
            startReporter(client)
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    /** Long-press fast-forward: speeds up until [endLongPressSpeed] restores the pick. */
    fun beginLongPressSpeed(speed: Float) = playerManager.beginTemporarySpeed(speed)

    fun endLongPressSpeed() = playerManager.endTemporarySpeed()

    fun setPlaybackSpeed(speed: Float) = playerManager.setPlaybackSpeed(speed)

    fun setVolume(volume: Float) = playerManager.setVolume(volume)

    fun selectSubtitle(track: SubtitleTrack?) = playerManager.selectSubtitle(track)

    fun selectAudioTrack(track: AudioTrack) = playerManager.selectAudioTrack(track)

    fun seekTo(positionMs: Long) {
        playerManager.seekTo(positionMs)
        reportAfterSeek()
    }

    fun stopPlayback() {
        loadJob?.cancel()
        queueJob?.cancel()
        if (!playerManager.queue.owns(this)) return
        finishCurrent(completed = false)
        playerManager.queue.detach(this)
        playerManager.stop()
    }

    private fun finishCurrent(completed: Boolean) {
        stopReporter()
        if (started) {
            val s = session
            val target = historyTarget
            val position = playerManager.state.value.positionMs / 1000
            val duration = playerManager.state.value.durationMs / 1000
            val watchedSeconds = (position - lastUsagePositionSeconds).coerceAtLeast(0L)
            val stopReport = report.copy(positionTicks = ((if (completed) duration else position) * 10_000_000L).coerceAtLeast(0))
            val bytes = playerManager.drainDownloadedBytes()
            started = false
            val previous = finishJob
            finishJob = appScope.launch {
                withContext(NonCancellable) {
                    previous?.join()
                    if (s != null) repository.clientFor(s.server.serviceType).reportPlaybackStopped(s, stopReport)
                    if (s != null) historyRepository.addDailyUsage(s.server, watchedSeconds, bytes)
                    if (s != null && target != null && completed) historyRepository.completePlayback(
                        PlaybackSource.ofServiceType(s.server.serviceType), s.server, target, duration)
                    else if (s != null && target != null) historyRepository.updateProgress(
                        PlaybackSource.ofServiceType(s.server.serviceType), s.server, target, position, duration
                    )
                }
            }
        }
    }

    /** Called when playback naturally reaches the end. */
    fun onPlaybackEnded() {
        finishCurrent(completed = true)
    }

    private fun startReporter(client: com.vibeplayer.app.data.remote.MediaServerClient) {
        stopReporter()
        reporterJob = viewModelScope.launch {
            val s = session ?: return@launch
            while (isActive) {
                delay(10_000)
                val positionMs = playerManager.state.value.positionMs
                val positionSeconds = positionMs / 1000
                val watchedSeconds = (positionSeconds - lastUsagePositionSeconds).coerceAtLeast(0L)
                if (positionMs > 0) {
                    client.reportPlaybackProgress(
                        s,
                        report.copy(positionTicks = (positionMs * 10_000L).coerceAtLeast(0))
                    )
                    historyTarget?.let { target ->
                        historyRepository.addDailyUsage(s.server, watchedSeconds, playerManager.drainDownloadedBytes())
                        lastUsagePositionSeconds = positionSeconds
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
