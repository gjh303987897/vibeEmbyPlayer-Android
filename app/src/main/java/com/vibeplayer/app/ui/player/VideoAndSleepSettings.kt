package com.vibeplayer.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.*

internal fun videoScaleLabel(mode: VideoScaleMode): Int = when (mode) {
    VideoScaleMode.FIT -> R.string.video_scale_fit
    VideoScaleMode.CROP -> R.string.video_scale_crop
    VideoScaleMode.ORIGINAL -> R.string.video_scale_original
}

@Composable
internal fun VideoScaleSettings(mode: VideoScaleMode, zoom: Float, onMode: (VideoScaleMode) -> Unit, onZoom: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(stringResource(R.string.video_scale_help), Modifier.padding(16.dp))
        VideoScaleMode.entries.forEach { candidate ->
            SettingRadioRow(stringResource(videoScaleLabel(candidate)), candidate == mode) { onMode(candidate) }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val label = stringResource(R.string.video_zoom)
            Text(stringResource(R.string.video_zoom_value, zoom), style = MaterialTheme.typography.titleSmall)
            Slider(zoom, onValueChange = onZoom, valueRange = MIN_VIDEO_ZOOM..MAX_VIDEO_ZOOM,
                modifier = Modifier.semantics { contentDescription = label })
            Text(stringResource(R.string.video_zoom_help), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onZoom(1f) }, enabled = zoom != 1f) { Text(stringResource(R.string.video_zoom_reset)) }
        }
    }
}

@Composable
internal fun sleepTimerSummary(state: SleepTimerState): String = when {
    state.expired -> stringResource(R.string.sleep_timer_expired)
    state.mode == SleepTimerMode.AFTER_VIDEO -> stringResource(R.string.sleep_timer_after_video)
    state.mode == SleepTimerMode.COUNTDOWN -> {
        val seconds = (state.remainingMs + 999) / 1000
        stringResource(R.string.sleep_timer_remaining, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    else -> stringResource(R.string.sleep_timer_off)
}

@Composable
internal fun SleepTimerSettings(
    timer: SleepTimerState, prepared: Boolean, canFinish: Boolean,
    onMinutes: (Int) -> Unit, onAfterVideo: () -> Unit, onCancel: () -> Unit, onCustom: () -> Unit
) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        Text(sleepTimerSummary(timer), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sleep_timer_help), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        SettingRadioRow(stringResource(R.string.sleep_timer_off), timer.mode == SleepTimerMode.OFF, onClick = onCancel)
        listOf(15, 30, 60).forEach { minutes ->
            SettingRadioRow(pluralStringResource(R.plurals.sleep_timer_minutes, minutes, minutes),
                timer.mode == SleepTimerMode.COUNTDOWN && timer.minutes == minutes, prepared) { onMinutes(minutes) }
        }
        ListItem(headlineContent = { Text(stringResource(R.string.sleep_timer_custom)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null) },
            modifier = Modifier.clickable(enabled = prepared, onClick = onCustom))
        SettingRadioRow(stringResource(R.string.sleep_timer_after_video), timer.mode == SleepTimerMode.AFTER_VIDEO,
            canFinish, onAfterVideo)
        if (!canFinish) Text(stringResource(R.string.sleep_timer_live_help),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SleepTimerCustomSettings(prepared: Boolean, onStart: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    val minutes = input.toIntOrNull()?.takeIf { it in 1..1440 }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(input, onValueChange = { value ->
            if (value.length <= 4 && value.all { it in '0'..'9' }) input = value
        }, label = { Text(stringResource(R.string.sleep_timer_custom)) },
            supportingText = { Text(stringResource(R.string.sleep_timer_custom_help)) },
            isError = input.isNotEmpty() && minutes == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { minutes?.let(onStart) }, enabled = prepared && minutes != null) {
            Text(stringResource(R.string.sleep_timer_start))
        }
    }
}

@Composable
private fun SettingRadioRow(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.padding(start = 16.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
    }
}
