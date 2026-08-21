package com.vibeplayer.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.model.PlaybackHistoryEntry
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.ui.navigation.Routes
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.nav_history)) })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            UsageSummary(state)
            SourceFilters(state.sourceFilter, viewModel::setSourceFilter)
            PaginationControls(
                page = state.page,
                totalPages = state.totalPages,
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
                onPrevious = viewModel::previousPage,
                onNext = viewModel::nextPage
            )

            if (state.entries.isEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.history_no_entries),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    state.groupedByDate.forEach { (date, entries) ->
                        item(key = "date_$date") {
                            Text(
                                text = date,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(entries, key = { it.id }) { entry ->
                            HistoryRow(
                                entry = entry,
                                onReplay = {
                                    if (entry.available) {
                                        if (entry.source == PlaybackSource.LINK) {
                                            navController.navigate(Routes.linkPlayer(entry.replayTarget))
                                        } else if (entry.source == PlaybackSource.LOCAL) {
                                            navController.navigate(Routes.localPlayer(entry.replayTarget))
                                        } else if (entry.serviceId.isNotBlank()) {
                                            navController.navigate(Routes.player(entry.serviceId, entry.replayTarget))
                                        }
                                    }
                                },
                                onDelete = { viewModel.deleteHistory(entry) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageSummary(state: HistoryUiState) {
    val totalWatchSeconds = state.usage.sumOf { it.watchSeconds }
    val totalBytesIn = state.usage.sumOf { it.networkBytesIn }
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            UsageStat(label = stringResource(R.string.history_watch_time), value = formatDuration(totalWatchSeconds))
            Spacer(Modifier.width(24.dp))
            UsageStat(label = stringResource(R.string.history_download), value = formatBytes(totalBytesIn))
        }
    }
}

@Composable
private fun UsageStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceFilters(selected: PlaybackSource?, onSelect: (PlaybackSource?) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.history_all)) }
            )
        }
        items(HistoryViewModel.SOURCE_FILTERS.filterNotNull(), key = { it.label }) { source ->
            FilterChip(
                selected = selected == source,
                onClick = { onSelect(source) },
                label = { Text(source.label) }
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun HistoryRow(entry: PlaybackHistoryEntry, onReplay: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.source.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (!entry.available && entry.serviceId.isBlank()) {
                Text(
                    text = stringResource(R.string.unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { entry.progress },
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = formatDuration(entry.positionSeconds) + " / " + formatDuration(entry.durationSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = entry.displayTarget,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = onReplay,
            enabled = entry.available && (entry.serviceId.isNotBlank() || entry.source == PlaybackSource.LINK || entry.source == PlaybackSource.LOCAL)
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.history_replay))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.history_delete))
        }
    }
}

@Composable
private fun PaginationControls(
    page: Int,
    totalPages: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrevious, enabled = hasPrevious) {
            Text(stringResource(R.string.history_previous))
        }
        Text(
            text = stringResource(R.string.history_page, page + 1, totalPages.coerceAtLeast(1)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onNext, enabled = hasNext) {
            Text(stringResource(R.string.history_next))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = TimeUnit.SECONDS.toHours(total)
    val m = TimeUnit.SECONDS.toMinutes(total) % 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}
