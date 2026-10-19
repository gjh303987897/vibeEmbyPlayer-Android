package com.vibeplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.MAX_SUBTITLE_DELAY_MS
import com.vibeplayer.app.player.SubtitleAppearance
import com.vibeplayer.app.ui.components.PlayerSubtitleOverlay
import kotlin.math.roundToInt

@Composable
internal fun SubtitleAppearanceSettings(current: SubtitleAppearance, onSave: (SubtitleAppearance) -> Unit) {
    var fontSize by rememberSaveable { mutableIntStateOf(current.fontSizeSp) }
    var bottomOffset by rememberSaveable { mutableIntStateOf(current.bottomOffsetDp) }
    var transparency by rememberSaveable { mutableIntStateOf(100 - current.backgroundOpacityPercent) }
    val draft = SubtitleAppearance(fontSize, bottomOffset, 100 - transparency)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).background(Color.DarkGray), contentAlignment = Alignment.BottomCenter) {
            PlayerSubtitleOverlay(stringResource(R.string.subtitle_preview), Modifier.padding(12.dp), draft)
        }
        Text(stringResource(R.string.subtitle_appearance_help), style = MaterialTheme.typography.bodySmall)
        AppearanceSlider(stringResource(R.string.subtitle_font_size), fontSize, 12..36, 23,
            onChange = { fontSize = it }, onFinished = { onSave(SubtitleAppearance(fontSize, bottomOffset, 100 - transparency)) })
        AppearanceSlider(stringResource(R.string.subtitle_bottom_offset), bottomOffset, 0..120, 23,
            onChange = { bottomOffset = it }, onFinished = { onSave(SubtitleAppearance(fontSize, bottomOffset, 100 - transparency)) })
        AppearanceSlider(stringResource(R.string.subtitle_background_transparency), transparency, 0..100, 19,
            suffix = "%", onChange = { transparency = it }, onFinished = { onSave(SubtitleAppearance(fontSize, bottomOffset, 100 - transparency)) })
        TextButton(onClick = {
            val defaults = SubtitleAppearance()
            fontSize = defaults.fontSizeSp
            bottomOffset = defaults.bottomOffsetDp
            transparency = 100 - defaults.backgroundOpacityPercent
            onSave(defaults)
        }) { Text(stringResource(R.string.subtitle_appearance_reset)) }
    }
}

@Composable
private fun AppearanceSlider(
    label: String, value: Int, range: IntRange, steps: Int, suffix: String = "",
    onChange: (Int) -> Unit, onFinished: () -> Unit
) {
    Text("$label: $value$suffix", style = MaterialTheme.typography.titleSmall)
    Slider(value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
        onValueChangeFinished = onFinished, modifier = Modifier.semantics { contentDescription = label })
}

@Composable
internal fun SubtitleTimingSettings(delayMs: Long, enabled: Boolean, onChange: (Long) -> Unit) {
    var slider by remember(delayMs) { mutableFloatStateOf(delayMs.toFloat()) }
    val limit = MAX_SUBTITLE_DELAY_MS.toFloat()
    val label = stringResource(R.string.subtitle_timing)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.subtitle_timing_value, slider / 1000f), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.subtitle_timing_help), style = MaterialTheme.typography.bodyMedium)
        Slider(slider, onValueChange = { slider = (it / 100).roundToInt() * 100f }, enabled = enabled,
            valueRange = -limit..limit, steps = 199,
            onValueChangeFinished = { onChange(slider.toLong()) },
            modifier = Modifier.semantics { contentDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = { onChange(delayMs - 100) }, enabled = enabled && delayMs > -MAX_SUBTITLE_DELAY_MS) {
                Text(stringResource(R.string.subtitle_earlier))
            }
            OutlinedButton(onClick = { onChange(delayMs + 100) }, enabled = enabled && delayMs < MAX_SUBTITLE_DELAY_MS) {
                Text(stringResource(R.string.subtitle_later))
            }
        }
        Button(onClick = { onChange(0) }, enabled = enabled && delayMs != 0L) {
            Text(stringResource(R.string.subtitle_timing_reset))
        }
    }
}
