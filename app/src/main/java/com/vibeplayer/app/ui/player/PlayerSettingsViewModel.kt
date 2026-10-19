package com.vibeplayer.app.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.player.PlaybackPerformance
import com.vibeplayer.app.player.PlaybackPerformanceMonitor
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.player.VideoDecodeMode
import com.vibeplayer.app.model.IptvQualityPreference
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerSettingsViewModel @Inject constructor(
    private val manager: PlayerManager,
    private val settings: SettingsDataStore,
    monitor: PlaybackPerformanceMonitor
) : ViewModel() {
    val preferences = manager.preferences
    val playerState = manager.state
    val diagnostics = manager.diagnostics
    val videoZoom = manager.videoZoom
    val sleepTimer = manager.sleepTimerState
    val decoder = manager.state.map { it.videoDecodeMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)
    val performance = preferences.map { it.showPerformance }.distinctUntilChanged()
        .flatMapLatest { if (it) monitor.samples() else flowOf(PlaybackPerformance()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PlaybackPerformance())
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed = _saveFailed.asStateFlow()

    fun setLongPressSpeed(speed: Float) = save { settings.setLongPressSpeed(speed) }
    fun setShowPerformance(enabled: Boolean) = save { settings.setShowPerformance(enabled) }
    fun setShowDiagnostics(enabled: Boolean) = save { settings.setShowDiagnostics(enabled) }
    fun setPlayerLayout(layout: com.vibeplayer.app.model.PlayerLayout) = save { settings.setPlayerLayout(layout) }
    fun setVideoDecodeMode(mode: VideoDecodeMode) = save { settings.setVideoDecodeMode(mode) }
    fun setIptvQuality(preference: IptvQualityPreference) = save { settings.setIptvQuality(preference) }
    fun togglePlayPause() = manager.togglePlayPause()
    fun seekTo(positionMs: Long) = manager.seekTo(positionMs)
    fun setPlaybackSpeed(speed: Float) = manager.setPlaybackSpeed(speed)
    fun setVolume(volume: Float) = manager.setVolume(volume)
    fun selectSubtitle(track: com.vibeplayer.app.player.SubtitleTrack?) = manager.selectSubtitle(track)
    fun setSubtitleAppearance(appearance: com.vibeplayer.app.player.SubtitleAppearance) =
        save { settings.setSubtitleAppearance(appearance) }
    fun setSubtitleDelay(delayMs: Long) = manager.setSubtitleDelay(delayMs)
    fun setVideoZoom(scale: Float) = manager.setVideoZoom(scale)
    fun setVideoScaleMode(mode: com.vibeplayer.app.player.VideoScaleMode) = save {
        settings.setVideoScaleMode(mode)
        manager.setVideoZoom(1f)
    }
    fun setSleepTimer(minutes: Int) = manager.setSleepTimer(minutes)
    fun setSleepAfterVideo() = manager.setSleepAfterVideo()
    fun cancelSleepTimer() = manager.cancelSleepTimer()

    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            _saveFailed.value = false
            try { action() } catch (error: java.io.IOException) {
                Log.w("PlayerSettings", "Unable to save player preferences (${error.javaClass.simpleName})")
                _saveFailed.value = true
            }
        }
    }
}
