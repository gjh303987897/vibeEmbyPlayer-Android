package com.vibeplayer.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.AudioDecodeInfo
import com.vibeplayer.app.player.AudioDecodeMode

/**
 * One-line explanation of *how the current audio track is being decoded*, for the formats a
 * phone cannot decode on its own.
 *
 * Streams are fetched with `static=true`, so the server sends the original file. When the
 * device has no MediaCodec decoder for it (AC-3 on non-Dolby phones, DTS / DTS-HD without a
 * DTS licence, TrueHD almost anywhere) Media3 normally drops the track and the user only
 * notices a mute picture. The FFmpeg decoder extension added on the player side is what
 * fixes that, and this row is what tells the user it is happening:
 *
 * - [AudioDecodeMode.SOFTWARE]: the on-device FFmpeg decoder took over - sound works, and
 *   the user learns why the app mentions "client decoding".
 * - [AudioDecodeMode.UNSUPPORTED]: neither MediaCodec nor FFmpeg can decode it (no native
 *   library in this build), so the "no sound" is named explicitly instead of being silent.
 * - [AudioDecodeMode.HARDWARE]: draws nothing.
 *
 * Kept inside the player's top bar so it never competes with the video surface, and
 * dismissible for the current item.
 */
@Composable
fun AudioDecodeNotice(info: AudioDecodeInfo?, modifier: Modifier = Modifier) {
    if (info == null || info.mode == AudioDecodeMode.HARDWARE) return
    var dismissed by remember(info.mimeType, info.mode) { mutableStateOf(false) }
    if (dismissed) return

    val text = when (info.mode) {
        AudioDecodeMode.SOFTWARE -> stringResource(R.string.player_audio_soft_decoding, info.codecLabel)
        AudioDecodeMode.UNSUPPORTED -> stringResource(R.string.player_audio_unsupported, info.codecLabel)
        AudioDecodeMode.HARDWARE -> return
    }
    // Every player screen draws this on the top bar's translucent black scrim.
    val tint = Color.White.copy(alpha = 0.85f)
    val accent = if (info.mode == AudioDecodeMode.UNSUPPORTED) {
        Color(0xFFFFB4AB) // errorContainer-ish on a dark scrim
    } else {
        tint
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (info.mode == AudioDecodeMode.UNSUPPORTED) {
                Icons.Outlined.Warning
            } else {
                Icons.Outlined.Info
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        IconButton(onClick = { dismissed = true }) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.message_dismiss),
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
