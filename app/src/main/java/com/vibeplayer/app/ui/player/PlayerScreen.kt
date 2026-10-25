package com.vibeplayer.app.ui.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.components.PlayerSubtitleOverlay
import com.vibeplayer.app.ui.components.KeepScreenOnDuringPlayback
import com.vibeplayer.app.ui.player.rememberPlayerGestureState
import com.vibeplayer.app.ui.components.PlaybackStatusOverlay
import com.vibeplayer.app.ui.components.bottomEdgeInsets

@Composable
fun PlayerScreen(
    serverId: String,
    itemId: String,
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeight by remember { mutableStateOf(0.dp) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val gestures = rememberPlayerGestureState()
    val playerSettings: PlayerSettingsViewModel = hiltViewModel()
    val queue by playerSettings.queue.collectAsState()
    val currentItemId = queue.items.getOrNull(queue.index)?.id ?: itemId
    var audioNoticeDismissed by rememberSaveable(serverId, currentItemId) { mutableStateOf(false) }
    val playerPreferences by playerSettings.preferences.collectAsState()
    val videoZoom by playerSettings.videoZoom.collectAsState()
    val sleepTimer by playerSettings.sleepTimer.collectAsState()
    val pipState = com.vibeplayer.app.ui.player.rememberPlayerPictureInPictureState()
    val pipMode = pipState.hideChrome
    val pipHost = com.vibeplayer.app.ui.player.LocalPlayerPip.current
    PlayerFullscreenEffect(fullscreen)
    // Playing keeps the display awake; pausing lets it sleep again.
    KeepScreenOnDuringPlayback(isPlaying = state.isPlaying, buffering = state.buffering, enabled = !sleepTimer.expired)

    LaunchedEffect(serverId, itemId) { viewModel.play(serverId, itemId) }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPlayback() } }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                IjkPlayerView(ctx, viewModel::setVideoSurface).apply {
                    player = viewModel.player
                    onVideoBoundsChanged = pipHost?.let { it::updateSourceBounds }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = {
                it.updateSurfaceGeneration(state.surfaceGeneration)
                if (state.isPrepared) it.updateVideoSize()
                it.updatePresentation(if (pipMode) com.vibeplayer.app.player.VideoScaleMode.FIT else playerPreferences.videoScaleMode,
                    if (pipMode) 1f else videoZoom)
                it.bitmapSubtitleFrame = state.bitmapSubtitleFrame
            },
            modifier = Modifier.fillMaxSize()
        )

        // Transparent gesture layer: below the control overlays (so a touch on a
        // button or the scrubber never reaches it), above the video surface.
        Box(
            Modifier
                .matchParentSize()
                .playerGestureSurface(
                    enabled = !locked && !pipMode,
                    zoomScale = videoZoom,
                    onZoomChange = playerSettings::setVideoZoom,
                    speedWhilePressed = playerPreferences.longPressSpeed,
                    gesture = gestures,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onLongPressSpeedStart = viewModel::beginLongPressSpeed,
                    onLongPressSpeedEnd = viewModel::endLongPressSpeed,
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek = viewModel::seekTo,
                    volume = state.volume,
                    onVolumeChange = viewModel::setVolume
                )
        )

        if (!locked && !pipMode) PlayerGestureOverlay(
            indicator = gestures.indicator,
            modifier = Modifier.matchParentSize()
        )

        PlayerSubtitleOverlay(state.subtitleText, Modifier.align(Alignment.BottomCenter).bottomEdgeInsets().padding(bottom = if (pipMode) 2.dp else if (controlsVisible && !locked) controlsHeight + 12.dp else 24.dp), playerPreferences.subtitleAppearance, compact = pipMode)

        // Loading + failure feedback: a playback error must never look like an
        // endless spinner.
        if (!locked && !pipMode) PlaybackStatusOverlay(
            buffering = state.buffering,
            error = state.error,
            onBack = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = if (controlsVisible) 1f else 0.5f }
        )

        if (!locked && !pipMode) AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                performance = {
                    if (playerPreferences.showPerformance || playerPreferences.showDiagnostics) PlayerPerformanceReadout(
                        playerSettings, compact = LocalConfiguration.current.screenWidthDp < 600
                    )
                },
                title = state.title,
                subtitle = state.subtitle,
                audioDecode = state.audioDecode.takeUnless { audioNoticeDismissed },
                onDismissAudioDecodeNotice = { audioNoticeDismissed = true },
                onBack = { navController.popBackStack() },
                actions = {
                    PlayerExtraActions(
                        audioTracks = state.audioTracks,
                        selectedAudioTrackKey = state.selectedAudioTrackKey,
                        onAudioTrackSelected = viewModel::selectAudioTrack
                    )
                }
            )
        }
        if (!locked && !pipMode) AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter).bottomEdgeInsets()
        ) {
            com.vibeplayer.app.ui.player.PlayerControls(playerSettings, onHeightChanged = { controlsHeight = it })
        }

        if (!pipMode) PlayerSettings(controlsVisible, locked, playerPreferences, playerSettings,
            fullscreen = fullscreen, onToggleFullscreen = { fullscreen = !fullscreen })

        if (!pipMode) PlayerScreenLock(
            locked = locked,
            controlsVisible = controlsVisible,
            onToggleLock = {
                locked = !locked
                controlsVisible = !locked
            }
        )
    }
}
