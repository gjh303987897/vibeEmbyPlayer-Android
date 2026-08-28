package com.vibeplayer.app.ui.webdav

import android.content.res.Configuration
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.vibeplayer.app.R
import java.util.concurrent.TimeUnit
import com.vibeplayer.app.ui.components.PlaybackStatusOverlay
import com.vibeplayer.app.ui.player.PlayerExtraActions
import com.vibeplayer.app.ui.player.PlayerFullscreenEffect

@Composable
fun WebDavPlayerScreen(
    serverId: String,
    encodedPath: String,
    navController: NavController,
    viewModel: WebDavPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    PlayerFullscreenEffect(fullscreen)

    LaunchedEffect(serverId, encodedPath) { viewModel.play(serverId, encodedPath) }

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

        if (controlsVisible) {
            WebDavTopBar(
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
            WebDavControls(
                isPlaying = state.isPlaying,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                playbackSpeed = state.playbackSpeed,
                volume = state.volume,
                onTogglePlay = viewModel::togglePlayPause,
                onSeek = viewModel::seekTo,
                onSpeedChange = viewModel::setPlaybackSpeed,
                onVolumeChange = viewModel::setVolume,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun WebDavTopBar(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
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
private fun WebDavControls(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    volume: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
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
                vertical = if (landscape) 4.dp else 0.dp
            )
    ) {
        if (landscape) {
            // Keep the scrubber and primary action on one compact row so the
            // video retains most of the available height in landscape.
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
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                WebDavSpeedSelector(
                    current = playbackSpeed,
                    onSpeedChange = onSpeedChange,
                    modifier = Modifier.weight(1.1f),
                    compact = true
                )
                WebDavVolumeControl(
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
                Text(text = formatMs(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(text = formatMs(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                PlayPauseButton(isPlaying, onTogglePlay)
            }
            WebDavSpeedSelector(current = playbackSpeed, onSpeedChange = onSpeedChange)
            WebDavVolumeControl(volume = volume, onVolumeChange = onVolumeChange)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onTogglePlay: () -> Unit) {
    IconButton(onClick = onTogglePlay, modifier = Modifier.padding(4.dp)) {
        Icon(
            imageVector = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
            tint = Color.White
        )
    }
}

private val webDavSpeedOptions = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

@Composable
private fun WebDavSpeedSelector(
    current: Float,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        webDavSpeedOptions.forEach { speed ->
            val selected = current == speed
            val label = if (speed == 1f) "1x" else "${if (speed % 1f == 0f) speed.toInt() else speed}x"
            Text(
                text = label,
                color = if (selected) Color.Black else Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        color = if (selected) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .clickable { onSpeedChange(speed) }
                    .padding(
                        horizontal = if (compact) 6.dp else 9.dp,
                        vertical = if (compact) 4.dp else 6.dp
                    )
            )
        }
    }
}

@Composable
private fun WebDavVolumeControl(
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
        Icon(Icons.AutoMirrored.Outlined.VolumeDown, contentDescription = null, tint = Color.White)
        Slider(
            value = volume,
            onValueChange = onVolumeChange,
            modifier = Modifier.weight(1f).padding(horizontal = if (compact) 4.dp else 8.dp)
        )
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = Color.White)
    }
}

private fun formatMs(ms: Long): String {
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
