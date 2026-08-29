package com.vibeplayer.app.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vibeplayer.app.R
import com.vibeplayer.app.data.local.db.entity.TransferStatus
import com.vibeplayer.app.data.local.db.entity.TransferTaskEntity
import com.vibeplayer.app.ui.components.AppInlineError

/**
 * Transfers (download / upload) screen. Renders the persisted transfer queue
 * with per-task progress and lifecycle controls, plus a clear-finished action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(viewModel: TransfersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.nav_transfers)) },
                actions = {
                    TextButton(
                        onClick = viewModel::clearFinished,
                        enabled = state.tasks.any { it.status in FINISHED_STATUSES }
                    ) {
                        Text(stringResource(R.string.transfer_clear_finished))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        if (state.tasks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.transfer_no_tasks),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.tasks, key = { it.id }) { task ->
                    TransferRow(
                        task = task,
                        statusLabel = viewModel.statusLabel(task.status),
                        onPause = { viewModel.pause(task.id) },
                        onResume = { viewModel.resume(task.id) },
                        onRetry = { viewModel.retry(task.id) },
                        onCancel = { viewModel.cancel(task.id) },
                        onRemove = { viewModel.remove(task.id) }
                    )
                }
            }
        }
    }
}

private val FINISHED_STATUSES = setOf(
    TransferStatus.DONE,
    TransferStatus.FAILED,
    TransferStatus.CANCELED
)

@Composable
private fun TransferRow(
    task: TransferTaskEntity,
    statusLabel: String,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit
) {
    val progress = if (task.totalBytes > 0) {
        (task.transferredBytes.toFloat() / task.totalBytes).coerceIn(0f, 1f)
    } else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLabel + " · " + task.serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (task.status) {
                    TransferStatus.RUNNING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = stringResource(R.string.pause))
                    }
                    TransferStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = stringResource(R.string.resume))
                    }
                    TransferStatus.FAILED, TransferStatus.QUEUED -> IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.transfer_retry))
                    }
                    TransferStatus.DONE, TransferStatus.CANCELED -> Unit
                }
                when (task.status) {
                    TransferStatus.RUNNING, TransferStatus.PAUSED, TransferStatus.QUEUED ->
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.transfer_cancel))
                        }
                    TransferStatus.DONE, TransferStatus.FAILED, TransferStatus.CANCELED ->
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove))
                        }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = formatBytes(task.transferredBytes) + " / " + formatBytes(task.totalBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            task.error?.let { err ->
                AppInlineError(
                    message = err,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
