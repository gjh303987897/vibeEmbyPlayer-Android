package com.vibeplayer.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.PlaybackQueueState

@Composable
internal fun PlaybackQueueSettings(queue: PlaybackQueueState, autoPlay: Boolean,
    onAutoPlay: (Boolean) -> Unit, onSelect: (Int) -> Unit) {
    val label = stringResource(R.string.player_auto_play_next)
    val listState = rememberLazyListState()
    LaunchedEffect(queue.index) { if (queue.index >= 0) listState.scrollToItem(queue.index) }
    Column {
        ListItem(headlineContent = { Text(label) },
            supportingContent = { Text(stringResource(if (queue.autoAdvanceAllowed) R.string.player_auto_play_next_help
                else R.string.player_queue_live_help)) },
            trailingContent = { Switch(autoPlay && queue.autoAdvanceAllowed, onAutoPlay,
                Modifier.semantics { contentDescription = label }, enabled = queue.autoAdvanceAllowed) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        HorizontalDivider()
        if (queue.items.isEmpty()) Text(stringResource(R.string.player_queue_empty), Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState) {
            itemsIndexed(queue.items, key = { _, item -> item.id }) { index, item ->
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (item.subtitle.isNotBlank()) ({ Text(item.subtitle, maxLines = 1,
                        overflow = TextOverflow.Ellipsis) }) else null,
                    leadingContent = { Text("${index + 1}") },
                    trailingContent = if (index == queue.index) ({ Icon(Icons.Outlined.PlayArrow,
                        stringResource(R.string.player_queue_current)) }) else null,
                    colors = ListItemDefaults.colors(containerColor = if (index == queue.index)
                        Color.White.copy(alpha = .12f) else Color.Transparent),
                    modifier = Modifier.semantics { selected = index == queue.index }
                        .clickable { onSelect(index) })
            }
        }
    }
}
