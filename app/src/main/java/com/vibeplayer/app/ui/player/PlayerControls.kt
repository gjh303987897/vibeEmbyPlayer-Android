package com.vibeplayer.app.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibeplayer.app.R
import com.vibeplayer.app.model.PlayerLayout
import com.vibeplayer.app.player.PLAYBACK_SPEEDS
import java.util.Locale

/** Every source uses the same layout and commands, including manual subtitle selection. */
@Composable
internal fun PlayerControls(viewModel: PlayerSettingsViewModel, modifier: Modifier = Modifier, onHeightChanged: (Dp) -> Unit = {}) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val trendy = preferences.layout == PlayerLayout.TRENDY
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.positionMs, state.durationMs, dragging) {
        if (!dragging) fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
    }
    BoxWithConstraints(modifier.onSizeChanged { size -> onHeightChanged(with(density) { size.height.toDp() }) }
        .fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val wide = maxWidth >= 640.dp && density.fontScale <= 1.3f
        Column(Modifier.fillMaxWidth()
            .background(Color.Black.copy(alpha = .68f), RoundedCornerShape(if (trendy) 20.dp else 0.dp))
            .padding(horizontal = 12.dp, vertical = if (trendy && !landscape) 8.dp else 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerSeekBar(value = fraction, dragging = dragging, enabled = state.isPrepared && state.durationMs > 0,
                onValueChange = { dragging = true; fraction = it }, onValueChangeFinished = {
                    viewModel.seekTo((fraction * state.durationMs).toLong()); dragging = false
                })
            Row(Modifier.fillMaxWidth()) {
                Text("${playbackTime(if (dragging) (fraction * state.durationMs).toLong() else state.positionMs)} / ${playbackTime(state.durationMs)}",
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SeekButton(-15, enabled = state.isPrepared && state.durationMs > 0) {
                        viewModel.seekTo((state.positionMs - 15_000).coerceAtLeast(0))
                    }
                    IconButton(onClick = viewModel::togglePlayPause, enabled = state.isPrepared) {
                        Icon(if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            stringResource(if (state.isPlaying) R.string.pause else R.string.play), tint = Color.White)
                    }
                    SeekButton(15, enabled = state.isPrepared && state.durationMs > 0) {
                        viewModel.seekTo((state.positionMs + 15_000).coerceAtMost(state.durationMs))
                    }
                }
                if (wide) {
                    Row(Modifier.align(Alignment.CenterStart).padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpeedMenu(state.playbackSpeed, viewModel::setPlaybackSpeed)
                        VolumeMenu(state.volume, viewModel::setVolume)
                    }
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 104.dp)) {
                        SubtitleSelector(state.subtitleTracks, state.selectedSubtitleKey, viewModel::selectSubtitle, compact = true)
                    }
                }
            }
            if (!wide) Row(Modifier.fillMaxWidth().padding(end = 104.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                SpeedMenu(state.playbackSpeed, viewModel::setPlaybackSpeed)
                SubtitleSelector(state.subtitleTracks, state.selectedSubtitleKey, viewModel::selectSubtitle, compact = true)
                VolumeMenu(state.volume, viewModel::setVolume)
            }
            if (trendy && !landscape) Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 104.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, tint = Color.White)
                Slider(value = state.volume, onValueChange = viewModel::setVolume,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }
    }
}

/** Material slider interactions/accessibility with a thin continuous track and circular thumb. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSeekBar(
    value: Float,
    dragging: Boolean,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val red = Color(0xFFFF0033)
    Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
        enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        thumb = {
            Box(Modifier.size(if (dragging) 16.dp else 12.dp)
                .background(if (enabled) red else Color.White.copy(alpha = .38f), CircleShape))
        },
        track = {
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val middle = size.height / 2
                val start = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                val end = if (layoutDirection == LayoutDirection.Rtl) size.width * (1f - value) else size.width * value
                drawLine(Color.White.copy(alpha = .28f), Offset(0f, middle), Offset(size.width, middle),
                    strokeWidth = size.height, cap = StrokeCap.Round)
                if (value > 0f) drawLine(if (enabled) red else Color.White.copy(alpha = .38f),
                    Offset(start, middle), Offset(end, middle), strokeWidth = size.height, cap = StrokeCap.Round)
            }
        })
}

@Composable
private fun SeekButton(seconds: Int, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (seconds < 0) R.string.player_seek_back else R.string.player_seek_forward)
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = label }) {
        Text(if (seconds > 0) "+$seconds" else seconds.toString(), color = if (enabled) Color.White else Color.Gray)
    }
}

@Composable
private fun SpeedMenu(speed: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    PipAutoEntryBlocker(expanded)
    val label = stringResource(R.string.player_speed)
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = label }) {
            Text("${speed}x", color = Color.White)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            PLAYBACK_SPEEDS.forEach { value ->
                DropdownMenuItem(text = { Text("${value}x") }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}

@Composable
private fun VolumeMenu(volume: Float, onChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    PipAutoEntryBlocker(expanded)
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.player_volume_control), tint = Color.White)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Slider(volume, onValueChange = onChange, modifier = Modifier.width(200.dp).padding(horizontal = 16.dp))
        }
    }
}

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
