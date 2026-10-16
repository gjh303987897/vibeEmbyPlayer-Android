package com.vibeplayer.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import com.vibeplayer.app.ui.components.AudioDecodeNotice
import com.vibeplayer.app.ui.components.topEdgeInsets

/** Performance is a trailing slot, so enabling it never pushes the header away from the top. */
@Composable
internal fun PlayerTopBar(
    title: String,
    subtitle: String,
    audioDecode: AudioDecodeInfo?,
    onBack: () -> Unit,
    performance: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().topEdgeInsets().background(Color.Black.copy(alpha = .5f))
        .padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = .7f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            performance()
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        AudioDecodeNotice(audioDecode, Modifier.padding(start = 48.dp, end = 8.dp))
    }
}
