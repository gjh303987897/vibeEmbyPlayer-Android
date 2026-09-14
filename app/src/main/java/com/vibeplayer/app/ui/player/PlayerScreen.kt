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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.player.SubtitleTrack
import java.util.concurrent.TimeUnit
import com.vibeplayer.app.ui.components.PlaybackStatusOverlay

@Composable
fun PlayerScreen(
    serverId: String,
    itemId: String,
    navController: NavController,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    PlayerFullscreenEffect(fullscreen)

    LaunchedEffect(itemId) { viewModel.play(itemId) }

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
        .clickable { controlsVisible = !controlsVisible }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.player
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading + failure feedback: a playback error must never look like an
        // endless spinner.
        PlaybackStatusOverlay(
            buffering = state.buffering,
            error = state.error,
            onBack = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = if (controlsVisible) 1f else 0.5f }
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            PlayerTopBar(
                title = state.title,
                subtitle = state.subtitle,
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
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(280)) + fadeIn(tween(280)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(220)) + fadeOut(tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter)
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
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .playerTopBarInsets()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(vertical = 8.dp)
            .zIndex(1f)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(text = title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            subtitle.takeIf { it.isNotBlank() }?.let {
                Text(text = it, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            }
        }
        actions()
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
    var sliderPosition by remember { mutableStateOf(0f) }
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
        if (landscape) {
            // In landscape the window is barely taller than the status-bar-free
            // video, so the six stacked portrait rows would cover most of the
            // picture. Scrubber + primary controls share compact rows instead.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlayPauseButton(isPlaying, onTogglePlay)
                Slider(
                    value = sliderPosition,
                    onValueChange = { sliderPosition = it },
                    onValueChangeFinished = { onSeek((sliderPosition * durationMs).toLong()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    text = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SpeedSelector(
                    current = playbackSpeed,
                    onSpeedChange = onSpeedChange,
                    modifier = Modifier.weight(1.15f),
                    compact = true
                )
                if (subtitleTracks.isNotEmpty()) {
                    SubtitleSelector(
                        subtitleTracks,
                        selectedSubtitleKey,
                        onSubtitleChange,
                        compact = true
                    )
                }
                VolumeControl(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
            }
        } else {
            Slider(
                value = sliderPosition,
                onValueChange = { sliderPosition = it },
                onValueChangeFinished = { onSeek((sliderPosition * durationMs).toLong()) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = formatTime(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlayPauseButton(isPlaying, onTogglePlay, contentPadding = 8.dp)
            }
            SpeedSelector(current = playbackSpeed, onSpeedChange = onSpeedChange)
            VolumeControl(volume = volume, onVolumeChange = onVolumeChange)
            if (subtitleTracks.isNotEmpty()) {
                SubtitleSelector(subtitleTracks, selectedSubtitleKey, onSubtitleChange)
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
private fun SubtitleSelector(
    tracks: List<SubtitleTrack>,
    selectedKey: String?,
    onSelect: (SubtitleTrack?) -> Unit,
    compact: Boolean = false
) {
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
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
