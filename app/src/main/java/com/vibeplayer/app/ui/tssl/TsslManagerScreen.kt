package com.vibeplayer.app.ui.tssl

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.TsslPackage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsslManagerScreen(
    navController: NavController,
    viewModel: TsslManagerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var exportTarget by remember { mutableStateOf<TsslPackage?>(null) }
    var backupTarget by remember { mutableStateOf<TsslPackage?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val bytes = runCatching {
                context.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
            }.getOrNull()
            bytes?.let(viewModel::import)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val target = exportTarget
        if (uri != null && target != null) {
            viewModel.exportBytes(target) { bytes ->
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(bytes) }
                }
            }
        }
        exportTarget = null
    }

    val sourcePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let(viewModel::packageFromTree)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.services_tssl)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Go back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.message?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::clearMessage) {
                                Icon(Icons.Outlined.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.tssl_packages_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                            ) {
                                Icon(Icons.Outlined.FileUpload, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tssl_import_restore))
                            }
                            OutlinedButton(
                                onClick = { sourcePicker.launch(null) },
                                enabled = !state.packagingBusy
                            ) {
                                Icon(Icons.Outlined.Movie, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tssl_package_hls_folder))
                            }
                        }
                    }
                }
            }

            if (state.packages.isEmpty()) {
                item {
                    Text(
                        if (state.loading) "" else stringResource(R.string.tssl_no_packages),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                    )
                }
            } else {
                items(state.packages, key = { it.fileName }) { pkg ->
                    TsslRow(
                        pkg = pkg,
                        busy = state.backupBusy,
                        onExport = {
                            exportTarget = pkg
                            exportLauncher.launch(pkg.fileName)
                        },
                        onDelete = { viewModel.delete(pkg) },
                        onBackup = { backupTarget = pkg }
                    )
                }
            }
        }
    }

    if (state.packagingBusy) {
        PackagingDialog(progress = state.packagingProgress ?: 0f)
    }

    if (backupTarget != null && !state.backupBusy) {
        val pkg = backupTarget!!
        BackupTargetDialog(
            targets = state.webDavTargets,
            onDismiss = { backupTarget = null },
            onConfirm = { target ->
                viewModel.backup(target, pkg)
                backupTarget = null
            }
        )
    }
}

@Composable
private fun TsslRow(
    pkg: TsslPackage,
    busy: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onBackup: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pkg.fileName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                pkg.identifierPreview?.let {
                    Text("ID: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${pkg.sizeBytes / 1024} KB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!busy) {
                IconButton(onClick = onBackup) { Icon(Icons.Outlined.CloudUpload, "Backup") }
                IconButton(onClick = onExport) { Icon(Icons.Outlined.SaveAlt, "Export") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun BackupTargetDialog(
    targets: List<ServerConfig>,
    onDismiss: () -> Unit,
    onConfirm: (ServerConfig) -> Unit
) {
    if (targets.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.tssl_no_webdav_target)) },
            text = { Text(stringResource(R.string.tssl_webdav_target_help)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_confirm)) } }
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(targets.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tssl_backup_to_webdav)) },
        text = {
            Column {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selected.name)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    targets.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.name) },
                            onClick = { selected = t; expanded = false }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.tssl_backup)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun PackagingDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.tssl_packaging_hls)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tssl_encrypting))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {}
    )
}
