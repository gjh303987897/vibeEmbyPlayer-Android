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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.data.local.datastore.TsslBackupSettings
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
    var s3SecretDraft by remember { mutableStateOf("") }

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

            item {
                TsslBackupSettingsCard(
                    settings = state.backupSettings,
                    webDavTargets = state.webDavTargets,
                    s3SecretConfigured = state.s3SecretConfigured,
                    backupBusy = state.backupBusy,
                    restoring = state.restoring,
                    restoreProgress = state.restoreProgress,
                    s3SecretDraft = s3SecretDraft,
                    onSecretDraftChange = { s3SecretDraft = it },
                    onTargetChange = viewModel::setBackupTarget,
                    onWebDavServiceChange = viewModel::setWebDavServiceId,
                    onWebDavPathChange = viewModel::setWebDavPath,
                    onS3EndpointChange = viewModel::setS3Endpoint,
                    onS3BucketChange = viewModel::setS3Bucket,
                    onS3RegionChange = viewModel::setS3Region,
                    onS3PrefixChange = viewModel::setS3Prefix,
                    onS3AccessKeyChange = viewModel::setS3AccessKey,
                    onTrustSelfSignedChange = viewModel::setTrustSelfSigned,
                    onSaveSecret = {
                        viewModel.saveS3Secret(s3SecretDraft)
                        s3SecretDraft = ""
                    },
                    onBackup = viewModel::backupAll,
                    onRestore = viewModel::restoreBackup
                )
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
private fun TsslBackupSettingsCard(
    settings: TsslBackupSettings,
    webDavTargets: List<ServerConfig>,
    s3SecretConfigured: Boolean,
    backupBusy: Boolean,
    restoring: Boolean,
    restoreProgress: Float?,
    s3SecretDraft: String,
    onSecretDraftChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onWebDavServiceChange: (String) -> Unit,
    onWebDavPathChange: (String) -> Unit,
    onS3EndpointChange: (String) -> Unit,
    onS3BucketChange: (String) -> Unit,
    onS3RegionChange: (String) -> Unit,
    onS3PrefixChange: (String) -> Unit,
    onS3AccessKeyChange: (String) -> Unit,
    onTrustSelfSignedChange: (Boolean) -> Unit,
    onSaveSecret: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var serviceMenuExpanded by remember { mutableStateOf(false) }
    val busy = backupBusy || restoring
    val targetOptions = listOf("none", "webdav", "s3")
    val targetLabels = listOf(
        stringResource(R.string.tssl_backup_target_none),
        stringResource(R.string.tssl_backup_target_webdav),
        stringResource(R.string.tssl_backup_target_s3)
    )
    val selectedService = webDavTargets.firstOrNull { it.id == settings.webDavServiceId }
        ?: webDavTargets.firstOrNull()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.tssl_backup_settings), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.tssl_backup_settings_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                targetOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = settings.target == option,
                        onClick = { onTargetChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = targetOptions.size),
                        label = { Text(targetLabels[index], maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }

            when (settings.target) {
                "webdav" -> {
                    OutlinedButton(
                        onClick = { serviceMenuExpanded = true },
                        enabled = webDavTargets.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedService?.name ?: stringResource(R.string.tssl_no_webdav_target))
                    }
                    DropdownMenu(
                        expanded = serviceMenuExpanded,
                        onDismissRequest = { serviceMenuExpanded = false }
                    ) {
                        webDavTargets.forEach { service ->
                            DropdownMenuItem(
                                text = { Text(service.name) },
                                onClick = {
                                    onWebDavServiceChange(service.id)
                                    serviceMenuExpanded = false
                                }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = settings.webDavPath,
                        onValueChange = onWebDavPathChange,
                        label = { Text(stringResource(R.string.tssl_backup_remote_path)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "s3" -> {
                    OutlinedTextField(
                        value = settings.s3Endpoint,
                        onValueChange = onS3EndpointChange,
                        label = { Text(stringResource(R.string.tssl_s3_endpoint)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.s3Bucket,
                        onValueChange = onS3BucketChange,
                        label = { Text(stringResource(R.string.tssl_s3_bucket)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = settings.s3Region,
                            onValueChange = onS3RegionChange,
                            label = { Text(stringResource(R.string.tssl_s3_region)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = settings.s3Prefix,
                            onValueChange = onS3PrefixChange,
                            label = { Text(stringResource(R.string.tssl_s3_prefix)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = settings.s3AccessKey,
                        onValueChange = onS3AccessKeyChange,
                        label = { Text(stringResource(R.string.tssl_s3_access_key)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = s3SecretDraft,
                            onValueChange = onSecretDraftChange,
                            label = { Text(stringResource(R.string.tssl_s3_secret)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onSaveSecret, enabled = !busy && (s3SecretDraft.isNotBlank() || s3SecretConfigured)) {
                            Text(stringResource(R.string.tssl_save_secret))
                        }
                    }
                    if (s3SecretConfigured) {
                        Text(
                            stringResource(R.string.tssl_s3_secret_saved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TsslSwitchRow(
                        label = stringResource(R.string.tssl_backup_trust_self_signed),
                        checked = settings.trustSelfSignedCertificate,
                        enabled = !busy,
                        onCheckedChange = onTrustSelfSignedChange
                    )
                }
            }

            if (backupBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            restoreProgress?.let {
                LinearProgressIndicator(
                    progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onBackup, enabled = !busy && settings.target != "none", modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tssl_backup_all))
                }
                OutlinedButton(onClick = onRestore, enabled = !busy && settings.target != "none", modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tssl_restore_backup))
                }
            }
        }
    }
}

@Composable
private fun TsslSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
