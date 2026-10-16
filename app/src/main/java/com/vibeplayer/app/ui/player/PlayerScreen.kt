package com.vibeplayer.app.ui.player

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.R
import com.vibeplayer.app.player.SubtitleTrack
import com.vibeplayer.app.ui.components.PlayerSubtitleOverlay
import com.vibeplayer.app.ui.components.KeepScreenOnDuringPlayback
import com.vibeplayer.app.ui.player.rememberPlayerGestureState
import com.vibeplayer.app.ui.components.PlaybackStatusOverlay
import com.vibeplayer.app.ui.components.bottomEdgeInsets
import java.util.concurrent.TimeUnit
import java.util.Locale

@Composable
fun PlayerScreen(
    serverId: String,
    itemId: String,
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
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

    LaunchedEffect(serverId, itemId) { viewModel.play(serverId, itemId) }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPlayback() } }

    // Detect natural end of playback to report it as complete.
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

        PlayerSubtitleOverlay(state.subtitleText, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))

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

        if (!locked) AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
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
                    PlayerExtraActions(
                        fullscreen = fullscreen,
                        onToggleFullscreen = { fullscreen = !fullscreen },
                        audioTracks = state.audioTracks,
                        selectedAudioTrackKey = state.selectedAudioTrackKey,
                        onAudioTrackSelected = viewModel::selectAudioTrack
                    )
                }
            )
        }
        if (!locked) AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter).bottomEdgeInsets()
        ) {
            ControlsBottom(
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playbackSpeed = state.playbackSpeed,
                volume = state.volume,
                onTogglePlay = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onSpeedChange = viewModel::setPlaybackSpeed,
                onVolumeChange = viewModel::setVolume,
                onSubtitleChange = viewModel::selectSubtitle,
                subtitleTracks = state.subtitleTracks,
                selectedSubtitleKey = state.selectedSubtitleKey
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
private fun ControlsBottom(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    volume: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onSubtitleChange: (SubtitleTrack?) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    selectedSubtitleKey: String?,
    modifier: Modifier = Modifier
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(positionMs, durationMs) {
        if (durationMs > 0) sliderPosition = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(
                horizontal = if (landscape) 16.dp else 8.dp,
                vertical = if (landscape) 2.dp else 0.dp
            )
    ) {
        Slider(
            value = sliderPosition,
            onValueChange = { sliderPosition = it },
            onValueChangeFinished = { onSeek((sliderPosition * durationMs).toLong()) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        if (landscape) {
            // Equal side weights keep the primary action on the video centre line.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 56.dp)
            ) {
                SpeedSelector(
                    current = playbackSpeed,
                    onSpeedChange = onSpeedChange,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
                PlayPauseButton(isPlaying, onTogglePlay)
                VolumeControl(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
            }
            if (subtitleTracks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.Center) {
                    SubtitleSelector(subtitleTracks, selectedSubtitleKey, onSubtitleChange, compact = true)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlayPauseButton(isPlaying, onTogglePlay, contentPadding = 8.dp)
            }
            SpeedSelector(current = playbackSpeed, onSpeedChange = onSpeedChange)
            VolumeControl(volume = volume, onVolumeChange = onVolumeChange,
                modifier = Modifier.padding(horizontal = 56.dp))
            if (subtitleTracks.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 56.dp), horizontalArrangement = Arrangement.Center) {
                    SubtitleSelector(subtitleTracks, selectedSubtitleKey, onSubtitleChange)
                }
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    contentPadding: Dp = 4.dp
) {
    IconButton(onClick = onTogglePlay, modifier = Modifier.padding(contentPadding)) {
        Icon(
            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
            tint = Color.White
        )
    }
}

private val speedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun SubtitleSelector(
    tracks: List<SubtitleTrack>,
    selectedKey: String?,
    onSelect: (SubtitleTrack?) -> Unit,
    compact: Boolean = false
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Button(
        onClick = { expanded = true },
        contentPadding = PaddingValues(
            horizontal = if (compact) 8.dp else 12.dp,
            vertical = if (compact) 2.dp else 8.dp
        )
    ) {
        Icon(Icons.Outlined.Subtitles, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(
            text = tracks.firstOrNull { it.key == selectedKey }?.label ?: "Subtitles",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text("Subtitles") },
            text = {
                Column {
                    TextButton(onClick = { onSelect(null); expanded = false }) { Text("Off") }
                    tracks.forEach { track ->
                        TextButton(onClick = { onSelect(track); expanded = false }) {
                            Text(if (track.key == selectedKey) "✓ ${track.label}" else track.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SpeedSelector(
    current: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (compact) {
            Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        } else {
            Arrangement.SpaceEvenly
        },
        verticalAlignment = Alignment.CenterVertically
    ) {
        speedOptions.forEach { speed ->
            val selected = current == speed
            val label = if (speed == 1f) "1x" else "${if (speed % 1f == 0f) speed.toInt() else speed}x"
            Text(
                text = label,
                color = if (selected) Color.Black else Color.White,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .background(
                        color = if (selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
                    )
                    .clickable { onSpeedChange(speed) }
                    .padding(
                        horizontal = if (compact) 6.dp else 12.dp,
                        vertical = if (compact) 4.dp else 6.dp
                    )
            )
        }
    }
}

@Composable
private fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 0.dp else 8.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.VolumeDown,
            contentDescription = null,
            tint = Color.White
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier.weight(1f).padding(horizontal = if (compact) 4.dp else 8.dp)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.VolumeUp,
            contentDescription = null,
            tint = Color.White
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
    val h = TimeUnit.SECONDS.toHours(totalSeconds)
    val m = TimeUnit.SECONDS.toMinutes(totalSeconds) % 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}
