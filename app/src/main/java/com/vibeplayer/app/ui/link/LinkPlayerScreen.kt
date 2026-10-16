package com.vibeplayer.app.ui.link

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.components.PlayerSubtitleOverlay
import com.vibeplayer.app.ui.components.KeepScreenOnDuringPlayback
import com.vibeplayer.app.ui.player.PlayerGestureOverlay
import com.vibeplayer.app.ui.player.PlayerSettings
import com.vibeplayer.app.ui.player.PlayerSettingsViewModel
import com.vibeplayer.app.ui.player.PlayerTopBar
import com.vibeplayer.app.ui.player.PlayerPerformanceReadout
import com.vibeplayer.app.ui.player.PlayerScreenLock
import com.vibeplayer.app.ui.player.playerGestureSurface
import com.vibeplayer.app.ui.player.rememberPlayerGestureState
import com.vibeplayer.app.ui.components.PlaybackStatusOverlay
import com.vibeplayer.app.ui.components.bottomEdgeInsets
import com.vibeplayer.app.ui.player.PlayerExtraActions
import com.vibeplayer.app.ui.player.PlayerFullscreenEffect
import java.util.concurrent.TimeUnit
import java.util.Locale

@Composable
fun LinkPlayerScreen(
    encodedUrl: String,
    navController: NavController,
    viewModel: LinkPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    val gestures = rememberPlayerGestureState()
    val playerSettings: PlayerSettingsViewModel = hiltViewModel()
    val playerPreferences by playerSettings.preferences.collectAsState()
    PlayerFullscreenEffect(fullscreen)
    // Playing keeps the display awake; pausing lets it sleep again.
    KeepScreenOnDuringPlayback(isPlaying = state.isPlaying, buffering = state.buffering)

    LaunchedEffect(encodedUrl) { viewModel.play(encodedUrl) }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPlayback() } }

    LaunchedEffect(state) {
        val ended = state.durationMs > 0 && state.positionMs >= state.durationMs - 500
        if (ended && state.isPrepared) {
            viewModel.onPlaybackEnded()
        }
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                IjkPlayerView(ctx, viewModel::setVideoSurface).apply {
                    player = viewModel.player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = {
                it.updateSurfaceGeneration(state.surfaceGeneration)
                if (state.isPrepared) it.updateVideoSize()
                it.bitmapSubtitleFrame = state.bitmapSubtitleFrame
            },
            modifier = Modifier.fillMaxSize()
        )

        state.subtitleText?.let { text ->
            PlayerSubtitleOverlay(text, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        }
        // Transparent gesture layer: below the control overlays (so a touch on a
        // button or the scrubber never reaches it), above the video surface.
        Box(
            Modifier
                .matchParentSize()
                .playerGestureSurface(
                    enabled = !locked,
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

        if (!locked) PlayerGestureOverlay(
            indicator = gestures.indicator,
            modifier = Modifier.matchParentSize()
        )


        // Loading + failure feedback: a playback error must never look like an
        // endless spinner.
        if (!locked) PlaybackStatusOverlay(
            buffering = state.buffering,
            error = state.error,
            onBack = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = if (controlsVisible) 1f else 0.5f }
        )

        if (controlsVisible && !locked) {
            PlayerTopBar(
                performance = {
                    if (playerPreferences.showPerformance) PlayerPerformanceReadout(
                        playerSettings, compact = LocalConfiguration.current.screenWidthDp < 600
                    )
                },
                title = state.title,
                subtitle = state.subtitle,
                audioDecode = state.audioDecode,
                onBack = { navController.popBackStack() },
                actions = {
                    com.vibeplayer.app.ui.player.SubtitleSelector(
                        state.subtitleTracks, state.selectedSubtitleKey, viewModel::selectSubtitle, compact = true
                    )
                    PlayerExtraActions(
                        fullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        audioTracks = state.audioTracks,
                        selectedAudioTrackKey = state.selectedAudioTrackKey,
                        onAudioTrackSelected = viewModel::selectAudioTrack
                    )
                }
            )
            LinkControls(
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                onTogglePlay = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                modifier = Modifier.align(Alignment.BottomCenter).bottomEdgeInsets()
            )
        }

        PlayerSettings(controlsVisible, locked, playerPreferences, playerSettings)

        PlayerScreenLock(
            locked = locked,
            controlsVisible = controlsVisible,
            onToggleLock = {
                locked = !locked
                controlsVisible = !locked
            }
        )
    }
}

@Composable
private fun LinkControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(positionMs, durationMs) {
        if (durationMs > 0) sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp)) {
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = { onSeek((sliderPosition * durationMs).toLong()) }
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = fmt(state = positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(text = fmt(state = durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp)) {
            IconButton(onClick = onTogglePlay, modifier = Modifier.padding(8.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                    tint = Color.White
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun fmt(state: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(state)
    val h = TimeUnit.SECONDS.toHours(totalSeconds)
    val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
