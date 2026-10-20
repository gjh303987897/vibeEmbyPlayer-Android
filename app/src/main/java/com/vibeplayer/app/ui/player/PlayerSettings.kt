package com.vibeplayer.app.ui.player

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibeplayer.app.R
import com.vibeplayer.app.player.LONG_PRESS_SPEEDS
import com.vibeplayer.app.player.PLAYBACK_SPEEDS
import com.vibeplayer.app.player.PlayerPreferences
import com.vibeplayer.app.player.VideoDecodeMode
import com.vibeplayer.app.model.PlayerLayout
import com.vibeplayer.app.ui.components.bottomEdgeInsets
import com.vibeplayer.app.ui.components.topEdgeInsets
import kotlin.math.roundToInt

private enum class PlayerSettingsPage { ROOT, HOLD_SPEED, SOURCE, SUBTITLES, PLAYBACK_SPEED, SUBTITLE_APPEARANCE, SUBTITLE_TIMING, VIDEO_SCALE, SLEEP_TIMER, SLEEP_CUSTOM, PICTURE_IN_PICTURE, LAYOUT, DECODE, PERFORMANCE, VIDEO_INFO }

/** Shared chrome for every source; performance remains visible when controls are hidden or locked. */
@Composable
fun BoxScope.PlayerSettings(
    controlsVisible: Boolean,
    locked: Boolean,
    preferences: PlayerPreferences,
    viewModel: PlayerSettingsViewModel,
    sourceSummary: String? = null,
    sourceSettings: (@Composable () -> Unit)? = null,
    fullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(PlayerSettingsPage.ROOT) }
    val dockBottom = if (preferences.layout == PlayerLayout.TRENDY &&
        LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE) 8.dp else 2.dp
    PipAutoEntryBlocker(expanded)

    // The two 48dp actions share the same bottom safe area as the control panel.
    Box(Modifier.align(Alignment.BottomEnd).bottomEdgeInsets().padding(end = 8.dp, bottom = dockBottom).zIndex(3f)) {
        if (controlsVisible && !locked) Row {
            IconButton(
                onClick = { expanded = true; page = PlayerSettingsPage.ROOT }
            ) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.player_settings), tint = Color.White)
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                    stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen),
                    tint = Color.White)
            }
        }
        if (expanded && !locked) {
            val decoder by viewModel.decoder.collectAsStateWithLifecycle()
            val playerState by viewModel.playerState.collectAsStateWithLifecycle()
            val zoom by viewModel.videoZoom.collectAsStateWithLifecycle()
            val sleepTimer by viewModel.sleepTimer.collectAsStateWithLifecycle()
            val saveFailed by viewModel.saveFailed.collectAsStateWithLifecycle()
            PlayerSettingsPopup(onDismiss = { expanded = false; page = PlayerSettingsPage.ROOT }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != PlayerSettingsPage.ROOT) IconButton(onClick = {
                        page = when (page) {
                            PlayerSettingsPage.SUBTITLE_APPEARANCE, PlayerSettingsPage.SUBTITLE_TIMING -> PlayerSettingsPage.SUBTITLES
                            PlayerSettingsPage.SLEEP_CUSTOM -> PlayerSettingsPage.SLEEP_TIMER
                            else -> PlayerSettingsPage.ROOT
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                    Text(stringResource(when (page) {
                        PlayerSettingsPage.HOLD_SPEED -> R.string.player_hold_speed
                        PlayerSettingsPage.SOURCE -> R.string.iptv_source_selection
                        PlayerSettingsPage.SUBTITLES -> R.string.subtitle_title
                        PlayerSettingsPage.PLAYBACK_SPEED -> R.string.player_speed
                        PlayerSettingsPage.SUBTITLE_APPEARANCE -> R.string.subtitle_appearance
                        PlayerSettingsPage.SUBTITLE_TIMING -> R.string.subtitle_timing
                        PlayerSettingsPage.VIDEO_SCALE -> R.string.video_scale
                        PlayerSettingsPage.SLEEP_TIMER -> R.string.sleep_timer
                        PlayerSettingsPage.SLEEP_CUSTOM -> R.string.sleep_timer_custom
                        PlayerSettingsPage.PICTURE_IN_PICTURE -> R.string.pip_title
                        PlayerSettingsPage.LAYOUT -> R.string.player_layout
                        PlayerSettingsPage.DECODE -> R.string.player_settings_decode
                        PlayerSettingsPage.PERFORMANCE -> R.string.player_settings_performance
                        PlayerSettingsPage.VIDEO_INFO -> R.string.video_info_title
                        PlayerSettingsPage.ROOT -> R.string.player_settings
                    }),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
                }
                val pageScroll = key(page) { rememberScrollState() }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                    .then(if (page == PlayerSettingsPage.SOURCE) Modifier else Modifier.verticalScroll(pageScroll))
                    .padding(bottom = 8.dp)) {
                    if (saveFailed) Text(stringResource(R.string.player_settings_save_failed),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    when {
                        page == PlayerSettingsPage.VIDEO_INFO -> VideoInfoSettings(playerState.mediaInfo)
                        page == PlayerSettingsPage.SOURCE && sourceSettings != null -> sourceSettings()
                        page == PlayerSettingsPage.SUBTITLES -> {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.subtitle_appearance)) },
                                supportingContent = { Text(stringResource(R.string.subtitle_appearance_summary)) },
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                                modifier = Modifier.clickable { page = PlayerSettingsPage.SUBTITLE_APPEARANCE }
                            )
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(stringResource(R.string.subtitle_timing)) },
                                supportingContent = { Text(stringResource(R.string.subtitle_timing_value, playerState.subtitleDelayMs / 1000f)) },
                                trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
                                modifier = Modifier.clickable { page = PlayerSettingsPage.SUBTITLE_TIMING }
                            )
                            HorizontalDivider()
                            SubtitleChoices(playerState.subtitleTracks, playerState.selectedSubtitleKey, viewModel::selectSubtitle,
                                Modifier.padding(horizontal = 16.dp))
                        }
                        page == PlayerSettingsPage.SUBTITLE_APPEARANCE -> SubtitleAppearanceSettings(
                            preferences.subtitleAppearance, viewModel::setSubtitleAppearance)
                        page == PlayerSettingsPage.SUBTITLE_TIMING -> SubtitleTimingSettings(
                            playerState.subtitleDelayMs, playerState.isPrepared, viewModel::setSubtitleDelay)
                        page == PlayerSettingsPage.VIDEO_SCALE -> VideoScaleSettings(
                            preferences.videoScaleMode, zoom, viewModel::setVideoScaleMode, viewModel::setVideoZoom)
                        page == PlayerSettingsPage.SLEEP_TIMER -> SleepTimerSettings(
                            sleepTimer, playerState.isPrepared, playerState.isPrepared && playerState.durationMs > 0,
                            viewModel::setSleepTimer, viewModel::setSleepAfterVideo, viewModel::cancelSleepTimer,
                            onCustom = { page = PlayerSettingsPage.SLEEP_CUSTOM })
                        page == PlayerSettingsPage.SLEEP_CUSTOM -> SleepTimerCustomSettings(playerState.isPrepared) {
                            viewModel.setSleepTimer(it)
                            page = PlayerSettingsPage.SLEEP_TIMER
                        }
                        page == PlayerSettingsPage.PICTURE_IN_PICTURE -> PictureInPictureSettings(
                            preferences.autoPictureInPicture, viewModel::setAutoPictureInPicture)
                        page == PlayerSettingsPage.PLAYBACK_SPEED -> SpeedChoices(
                            PLAYBACK_SPEEDS, playerState.playbackSpeed, viewModel::setPlaybackSpeed
                        )
                        page == PlayerSettingsPage.HOLD_SPEED -> {
                            Text(stringResource(R.string.player_hold_speed_help), modifier = Modifier.padding(16.dp))
                            SpeedChoices(LONG_PRESS_SPEEDS, preferences.longPressSpeed, viewModel::setLongPressSpeed)
                        }
                        page == PlayerSettingsPage.ROOT -> {
                            PlayerSettingMenuRow(Icons.Outlined.Subtitles, stringResource(R.string.subtitle_title),
                                playerState.subtitleTracks.firstOrNull { it.key == playerState.selectedSubtitleKey }?.label
                                    ?: stringResource(R.string.subtitle_off)) { page = PlayerSettingsPage.SUBTITLES }
                            PlayerSettingMenuRow(Icons.Outlined.Bedtime, stringResource(R.string.sleep_timer),
                                sleepTimerSummary(sleepTimer)) { page = PlayerSettingsPage.SLEEP_TIMER }
                            PlayerSettingMenuRow(Icons.Outlined.Speed, stringResource(R.string.player_speed),
                                "${playerState.playbackSpeed}x") { page = PlayerSettingsPage.PLAYBACK_SPEED }
                            PlayerSettingMenuRow(Icons.Outlined.AspectRatio, stringResource(R.string.video_scale),
                                stringResource(videoScaleLabel(preferences.videoScaleMode))) { page = PlayerSettingsPage.VIDEO_SCALE }
                            PlayerSettingMenuRow(Icons.Outlined.Info, stringResource(R.string.video_info_title)) {
                                page = PlayerSettingsPage.VIDEO_INFO
                            }
                            if (sourceSettings != null) PlayerSettingMenuRow(Icons.AutoMirrored.Outlined.AltRoute,
                                stringResource(R.string.iptv_source_selection), sourceSummary) { page = PlayerSettingsPage.SOURCE }
                            PlayerSettingMenuRow(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.pip_title)) {
                                page = PlayerSettingsPage.PICTURE_IN_PICTURE
                            }
                            PlayerSettingMenuRow(Icons.Outlined.TouchApp, stringResource(R.string.player_hold_speed),
                                "${preferences.longPressSpeed}x") { page = PlayerSettingsPage.HOLD_SPEED }
                            PlayerSettingMenuRow(Icons.Outlined.Memory, stringResource(R.string.player_settings_decode),
                                stringResource(decodeLabel(preferences.videoDecodeMode))) { page = PlayerSettingsPage.DECODE }
                            PlayerSettingMenuRow(Icons.Outlined.QueryStats, stringResource(R.string.player_settings_performance)) {
                                page = PlayerSettingsPage.PERFORMANCE
                            }
                            PlayerSettingMenuRow(Icons.Outlined.Dashboard, stringResource(R.string.player_layout)) {
                                page = PlayerSettingsPage.LAYOUT
                            }
                        }
                        page == PlayerSettingsPage.LAYOUT -> {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                com.vibeplayer.app.ui.settings.LayoutChoices(preferences.layout == com.vibeplayer.app.model.PlayerLayout.TRENDY) {
                                    viewModel.setPlayerLayout(if (it) com.vibeplayer.app.model.PlayerLayout.TRENDY else com.vibeplayer.app.model.PlayerLayout.TRADITIONAL)
                                }
                            }
                        }
                        page == PlayerSettingsPage.DECODE -> {
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
                        page == PlayerSettingsPage.PERFORMANCE -> {
                            val performanceLabel = stringResource(R.string.player_performance_overlay)
                            val diagnosticsLabel = stringResource(R.string.player_diagnostics)
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(performanceLabel) },
                                supportingContent = { Text(stringResource(R.string.player_performance_overlay_help)) },
                                trailingContent = { Switch(checked = preferences.showPerformance, onCheckedChange = viewModel::setShowPerformance,
                                    modifier = Modifier.semantics { contentDescription = performanceLabel }) }
                            )
                            Text(stringResource(R.string.player_performance_scope), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp))
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(diagnosticsLabel) },
                                supportingContent = { Text(stringResource(R.string.player_diagnostics_help)) },
                                trailingContent = { Switch(checked = preferences.showDiagnostics, onCheckedChange = viewModel::setShowDiagnostics,
                                    modifier = Modifier.semantics { contentDescription = diagnosticsLabel }) }
                            )
                            Text(stringResource(R.string.player_diagnostics_scope), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
    }
    if ((preferences.showPerformance || preferences.showDiagnostics) && (!controlsVisible || locked)) {
        PlayerPerformanceReadout(viewModel,
            modifier = Modifier.align(Alignment.TopEnd).topEdgeInsets().padding(top = 4.dp, end = 8.dp).zIndex(3f))
    }
}

@Composable
private fun SpeedChoices(speeds: List<Float>, selected: Float, onSelect: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        speeds.forEach { speed ->
            Row(Modifier.fillMaxWidth().selectable(selected == speed,
                role = Role.RadioButton, onClick = { onSelect(speed) })
                .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == speed, onClick = null)
                Text("${speed}x", Modifier.padding(start = 16.dp))
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
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val unavailable = stringResource(R.string.player_metric_unavailable)
    val labels = buildList {
        if (preferences.showPerformance) {
            add("CPU ${performance.cpuPercent?.roundToInt()?.let { "$it%" } ?: unavailable}")
            add("GPU ${performance.gpuPercent?.roundToInt()?.let { "$it%" } ?: unavailable}")
        }
        if (preferences.showDiagnostics) {
            add("FPS ${diagnostics.outputFps?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—"}")
            add("NET ${diagnostics.networkBytesPerSecond?.let(::formatNetworkSpeed) ?: "—"}")
        }
    }
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

private fun formatNetworkSpeed(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MiB/s", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KiB/s", bytes / 1024.0)
    else -> "$bytes B/s"
}

private fun decodeLabel(mode: VideoDecodeMode): Int = when (mode) {
    VideoDecodeMode.HARDWARE -> R.string.player_decode_hardware
    VideoDecodeMode.SOFTWARE -> R.string.player_decode_software
}
