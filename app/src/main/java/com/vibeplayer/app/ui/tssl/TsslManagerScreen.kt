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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.data.local.datastore.TsslBackupSettings
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.TsslPackage
import com.vibeplayer.app.ui.components.AppMessageCard

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
                    AppMessageCard(
                        message = message,
                        tone = state.messageTone,
                        onDismiss = viewModel::clearMessage
                    )
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
    var showServicePicker by remember { mutableStateOf(false) }
    var webDavPathFocused by remember { mutableStateOf(false) }
    var webDavPathDraft by rememberSaveable { mutableStateOf(settings.webDavPath) }
    androidx.compose.runtime.LaunchedEffect(settings.webDavPath) {
        if (!webDavPathFocused && webDavPathDraft != settings.webDavPath) {
            webDavPathDraft = settings.webDavPath
        }
    }
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
                        onClick = { showServicePicker = true },
                        enabled = webDavTargets.isNotEmpty() && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedService?.name ?: stringResource(R.string.tssl_no_webdav_target))
                    }
                    OutlinedTextField(
                        value = webDavPathDraft,
                        onValueChange = {
                            webDavPathDraft = it
                            onWebDavPathChange(it)
                        },
                        label = { Text(stringResource(R.string.tssl_backup_remote_path)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { webDavPathFocused = it.isFocused }
                    )
                }
                "s3" -> {
                    OutlinedTextField(value = settings.s3Endpoint, onValueChange = onS3EndpointChange,
                        label = { Text(stringResource(R.string.tssl_s3_endpoint)) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = settings.s3Bucket, onValueChange = onS3BucketChange,
                        label = { Text(stringResource(R.string.tssl_s3_bucket)) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = settings.s3Region, onValueChange = onS3RegionChange,
                            label = { Text(stringResource(R.string.tssl_s3_region)) }, singleLine = true,
                            enabled = !busy, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = settings.s3Prefix, onValueChange = onS3PrefixChange,
                            label = { Text(stringResource(R.string.tssl_s3_prefix)) }, singleLine = true,
                            enabled = !busy, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(value = settings.s3AccessKey, onValueChange = onS3AccessKeyChange,
                        label = { Text(stringResource(R.string.tssl_s3_access_key)) }, singleLine = true,
                        enabled = !busy, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = s3SecretDraft, onValueChange = onSecretDraftChange,
                            label = { Text(stringResource(R.string.tssl_s3_secret)) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onSaveSecret, enabled = !busy && (s3SecretDraft.isNotBlank() || s3SecretConfigured)) {
                            Text(stringResource(R.string.tssl_save_secret))
                        }
                    }
                    if (s3SecretConfigured) {
                        Text(stringResource(R.string.tssl_s3_secret_saved), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    TsslSwitchRow(label = stringResource(R.string.tssl_backup_trust_self_signed),
                        checked = settings.trustSelfSignedCertificate, enabled = !busy,
                        onCheckedChange = onTrustSelfSignedChange)
                }
            }

            if (backupBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            restoreProgress?.let { progress ->
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
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

    if (showServicePicker) {
        WebDavServicePickerDialog(
            targets = webDavTargets,
            selectedId = selectedService?.id,
            onDismiss = { showServicePicker = false },
            onSelect = { service ->
                onWebDavServiceChange(service.id)
                showServicePicker = false
            }
        )
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
                Text(
                    text = stringResource(R.string.tssl_file_name, pkg.fileName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pkg.identifierPreview != null) {
                    val identifier = pkg.identifierPreview
                    Text(
                        text = stringResource(R.string.tssl_identifier, identifier),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (!pkg.isValid) {
                    Text(
                        text = stringResource(R.string.tssl_invalid_package),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
    var selectedId by remember(targets) { mutableStateOf(targets.first().id) }
    val selected = targets.firstOrNull { it.id == selectedId } ?: targets.first()
    WebDavServicePickerDialog(
        targets = targets,
        selectedId = selected.id,
        title = stringResource(R.string.tssl_backup_to_webdav),
        confirmLabel = stringResource(R.string.tssl_backup),
        onDismiss = onDismiss,
        onSelect = { selectedId = it.id },
        onConfirm = { onConfirm(selected) }
    )
}

/**
 * Centered WebDAV service picker used by both settings and one-off backup.
 * DropdownMenu is positioned relative to the dialog's window and can appear
 * detached on edge-to-edge/cutout devices; a bounded dialog keeps the list
 * centered, scrollable and visually consistent instead.
 */
@Composable
private fun WebDavServicePickerDialog(
    targets: List<ServerConfig>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (ServerConfig) -> Unit,
    title: String = stringResource(R.string.tssl_select_webdav),
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .heightIn(max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(1.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(targets, key = { it.id }) { service ->
                        val selected = service.id == selectedId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable { onSelect(service) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Cloud,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    service.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    service.baseUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            RadioButton(selected = selected, onClick = { onSelect(service) })
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (confirmLabel != null && onConfirm != null) {
                        TextButton(onClick = onConfirm) { Text(confirmLabel) }
                    }
                }
            }
        }
    }
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
