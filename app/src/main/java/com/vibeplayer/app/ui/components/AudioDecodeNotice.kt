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
 * Explains the selected audio decoder without inferring the phone's codec capabilities.
 *
 * Streams are fetched with `static=true`, so the server sends the original file. When the
 * bundled FFmpeg path handles the track, this row tells the user it is happening:
 *
 * - [AudioDecodeMode.SOFTWARE]: the on-device FFmpeg decoder is selected, and
 *   the user learns why the app mentions "client decoding".
 * - [AudioDecodeMode.UNSUPPORTED]: no active audio decoder was reported, so possible
 *   missing sound is named explicitly without guessing whether a library is installed.
 * - [AudioDecodeMode.HARDWARE]: draws nothing.
 *
 * Kept inside the player's top bar so it never competes with the video surface, and
 * dismissed by the playback screen, which retains dismissal when this row leaves composition.
 */
@Composable
fun AudioDecodeNotice(info: AudioDecodeInfo?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (info == null || info.mode == AudioDecodeMode.HARDWARE) return

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
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.message_dismiss),
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
