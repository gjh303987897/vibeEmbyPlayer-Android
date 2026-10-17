package com.vibeplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibeplayer.app.R
import com.vibeplayer.app.player.LONG_PRESS_SPEEDS
import com.vibeplayer.app.player.PlayerPreferences
import com.vibeplayer.app.player.VideoDecodeMode
import com.vibeplayer.app.ui.components.bottomEdgeInsets
import com.vibeplayer.app.ui.components.topEdgeInsets
import kotlin.math.roundToInt

/** Shared chrome for every source; performance remains visible when controls are hidden or locked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.PlayerSettings(
    controlsVisible: Boolean,
    locked: Boolean,
    preferences: PlayerPreferences,
    viewModel: PlayerSettingsViewModel,
    sourceSummary: String? = null,
    sourceSettings: (@Composable () -> Unit)? = null
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var speedPage by rememberSaveable { mutableStateOf(false) }
    var sourcePage by rememberSaveable { mutableStateOf(false) }

    if (controlsVisible && !locked) {
        IconButton(
            onClick = { expanded = true; speedPage = false; sourcePage = false },
            modifier = Modifier.align(Alignment.BottomEnd).bottomEdgeInsets().padding(end = 8.dp, bottom = 8.dp)
                .background(Color.Black.copy(alpha = .65f), CircleShape).zIndex(3f)
        ) {
            Icon(Icons.Outlined.Settings, stringResource(R.string.player_settings), tint = Color.White)
        }
    }
    if (preferences.showPerformance && (!controlsVisible || locked)) {
        PlayerPerformanceReadout(viewModel,
            modifier = Modifier.align(Alignment.TopEnd).topEdgeInsets().padding(top = 4.dp, end = 8.dp)
                .zIndex(3f)
        )
    }
    if (expanded && !locked) {
        val decoder by viewModel.decoder.collectAsStateWithLifecycle()
        val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
        ModalBottomSheet(
            onDismissRequest = { expanded = false; speedPage = false; sourcePage = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (speedPage || sourcePage) IconButton(onClick = { speedPage = false; sourcePage = false }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
                Text(stringResource(when {
                    speedPage -> R.string.player_hold_speed
                    sourcePage -> R.string.iptv_source_selection
                    else -> R.string.player_settings
                }),
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            }
            if (!speedPage && !sourcePage) TabRow(selectedTabIndex = tab) {
                listOf(R.string.player_settings_playback, R.string.player_settings_decode, R.string.player_settings_performance)
                    .forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                    }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)
                .then(if (sourcePage) Modifier else Modifier.verticalScroll(rememberScrollState()))
                .padding(bottom = 24.dp)) {
                if (saveFailed) Text(stringResource(R.string.player_settings_save_failed),
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                when {
                    sourcePage && sourceSettings != null -> sourceSettings()
                    speedPage -> {
                        Text(stringResource(R.string.player_hold_speed_help), modifier = Modifier.padding(16.dp))
                        LONG_PRESS_SPEEDS.forEach { speed ->
                            Row(Modifier.fillMaxWidth().selectable(preferences.longPressSpeed == speed,
                                role = Role.RadioButton, onClick = { viewModel.setLongPressSpeed(speed) })
                                .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = preferences.longPressSpeed == speed, onClick = null)
                                Text("${speed}x", Modifier.padding(start = 16.dp))
                            }
                        }
                    }
                    tab == 0 -> {
                        if (sourceSettings != null) ListItem(
                            headlineContent = { Text(stringResource(R.string.iptv_source_selection)) },
                            supportingContent = { Text(sourceSummary.orEmpty()) },
                            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                            modifier = Modifier.clickable { sourcePage = true }
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.player_hold_speed)) },
                            supportingContent = { Text(stringResource(R.string.player_hold_speed_help)) },
                            trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${preferences.longPressSpeed}x")
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null)
                            } },
                            modifier = Modifier.clickable { speedPage = true }
                        )
                    }
                    tab == 1 -> {
                        Text(stringResource(R.string.player_decode_help), modifier = Modifier.padding(16.dp))
                        VideoDecodeMode.entries.forEach { mode ->
                            Row(Modifier.fillMaxWidth().selectable(preferences.videoDecodeMode == mode,
                                role = Role.RadioButton, onClick = { viewModel.setVideoDecodeMode(mode) })
                                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = preferences.videoDecodeMode == mode, onClick = null)
                                Text(stringResource(decodeLabel(mode)), Modifier.padding(start = 16.dp))
                            }
                        }
                        Text(stringResource(R.string.player_current_decoder,
                            decoder?.let { stringResource(decodeLabel(it)) } ?: stringResource(R.string.player_metric_unavailable)),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                    }
                    else -> {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.player_performance_overlay)) },
                            supportingContent = { Text(stringResource(R.string.player_performance_overlay_help)) },
                            trailingContent = { Switch(checked = preferences.showPerformance, onCheckedChange = viewModel::setShowPerformance) }
                        )
                        Text(stringResource(R.string.player_performance_scope), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }
}

/** Fits beside the header actions in portrait; also used independently when controls are hidden. */
@Composable
internal fun PlayerPerformanceReadout(
    viewModel: PlayerSettingsViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val performance by viewModel.performance.collectAsStateWithLifecycle()
    val unavailable = stringResource(R.string.player_metric_unavailable)
    val labels = listOf(
        "CPU ${performance.cpuPercent?.roundToInt()?.let { "$it%" } ?: unavailable}",
        "GPU ${performance.gpuPercent?.roundToInt()?.let { "$it%" } ?: unavailable}"
    )
    val readout = modifier.background(Color.Black.copy(alpha = .75f), RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp)
    val content: @Composable () -> Unit = {
        labels.forEach { label ->
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace, maxLines = 1)
        }
    }
    if (compact) Column(readout, content = { content() })
    else Row(readout, horizontalArrangement = Arrangement.spacedBy(12.dp), content = { content() })
}

private fun decodeLabel(mode: VideoDecodeMode): Int = when (mode) {
    VideoDecodeMode.HARDWARE -> R.string.player_decode_hardware
    VideoDecodeMode.SOFTWARE -> R.string.player_decode_software
}
