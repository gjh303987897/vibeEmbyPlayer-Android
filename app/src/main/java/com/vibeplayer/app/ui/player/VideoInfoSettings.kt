package com.vibeplayer.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.PlaybackMediaInfo
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun VideoInfoSettings(info: PlaybackMediaInfo) {
    val unknown = stringResource(R.string.video_info_unknown)
    val video = info.video
    val audio = info.audio
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.video_info_help), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoSection(stringResource(R.string.video_info_video))
        InfoValue(stringResource(R.string.video_info_resolution),
            if (video?.width != null && video.height != null) "${video.width} × ${video.height}" else unknown)
        InfoValue(stringResource(R.string.video_info_frame_rate),
            video?.frameRate?.let { "${number(it, 3)} fps" } ?: unknown)
        InfoValue(stringResource(R.string.video_info_video_codec), video?.codec?.uppercase(Locale.ROOT) ?: unknown)
        InfoValue(stringResource(R.string.video_info_video_bitrate), bitrate(video?.bitrate) ?: unknown)
        InfoValue(stringResource(R.string.video_info_profile), video?.profile ?: unknown)
        InfoValue(stringResource(R.string.video_info_pixel_format), video?.pixelFormat ?: unknown)
        InfoSection(stringResource(R.string.video_info_audio))
        InfoValue(stringResource(R.string.video_info_audio_codec), audio?.codec?.uppercase(Locale.ROOT) ?: unknown)
        InfoValue(stringResource(R.string.video_info_audio_bitrate), bitrate(audio?.bitrate) ?: unknown)
        InfoValue(stringResource(R.string.video_info_sample_rate), audio?.sampleRateHz?.let { "$it Hz" } ?: unknown)
        InfoValue(stringResource(R.string.video_info_channels), audio?.channelCount?.let {
            stringResource(R.string.video_info_channel_count, it)
        } ?: unknown)
    }
}

@Composable
private fun InfoSection(label: String) {
    Text(label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun InfoValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

private fun number(value: Double, decimals: Int): String = NumberFormat.getNumberInstance().apply {
    maximumFractionDigits = decimals
    isGroupingUsed = false
}.format(value)

private fun bitrate(value: Long?): String? = value?.let {
    when {
        it >= 1_000_000 -> "${number(it / 1_000_000.0, 2)} Mbps"
        it >= 1_000 -> "${number(it / 1_000.0, 2)} kbps"
        else -> "$it bps"
    }
}
