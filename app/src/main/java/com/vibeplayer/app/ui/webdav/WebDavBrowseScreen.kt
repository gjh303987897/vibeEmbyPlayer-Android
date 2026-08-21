package com.vibeplayer.app.ui.webdav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.model.WebDavItem
import com.vibeplayer.app.service.TransferService
import com.vibeplayer.app.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavBrowseScreen(
    serverId: String,
    navController: NavController,
    viewModel: WebDavBrowseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var passwordInput by remember { mutableStateOf("") }

    var pendingDownload by remember { mutableStateOf<WebDavItem?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val item = pendingDownload
        pendingDownload = null
        if (treeUri != null && item != null) {
            viewModel.download(item, treeUri.toString())
            TransferService.start(context)
        }
    }

    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.upload(uri)
    }

    LaunchedEffect(serverId) {
        viewModel.load(serverId)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(uiState.currentDirectoryName) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.path.isEmpty()) navController.popBackStack()
                            else viewModel.goUp()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Go up")
                    }
                },
                actions = {
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                    }
                    IconButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Outlined.Upload, contentDescription = "Upload")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        when {
            uiState.loading && uiState.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null && uiState.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(uiState.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(uiState.items, key = { it.path }) { item ->
                        WebDavRow(
                            item = item,
                            onOpen = {
                                if (item.isDirectory) {
                                    viewModel.navigateTo(item.path)
                                } else {
                                    navController.navigate(Routes.webdavPlayer(serverId, item.path))
                                }
                            },
                            onDownload = {
                                pendingDownload = item
                                folderPicker.launch(null)
                            }
                        )
                    }
                }
            }
        }
    }

    if (uiState.needPassword) {
        AlertDialog(
            onDismissRequest = { navController.popBackStack() },
            title = { Text(uiState.server?.name ?: "WebDAV") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = context.getString(R.string.webdav_enter_password),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(uiState.server?.username ?: "User") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passwordInput.isNotBlank(),
                    onClick = {
                        viewModel.savePassword(passwordInput.trim())
                        passwordInput = ""
                    }
                ) {
                    Text(context.getString(R.string.open))
                }
            },
            dismissButton = {
                TextButton(onClick = { navController.popBackStack() }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text(context.getString(R.string.webdav_new_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(context.getString(R.string.webdav_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFolderName.isNotBlank(),
                    onClick = {
                        viewModel.createDirectory(newFolderName)
                        newFolderName = ""
                        showNewFolder = false
                    }
                ) {
                    Text(context.getString(R.string.open))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) {
                    Text(context.getString(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun WebDavRow(item: WebDavItem, onOpen: () -> Unit, onDownload: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = {
            val icon = when {
                item.isDirectory -> Icons.Outlined.Folder
                item.isVideo -> Icons.Outlined.VideoLibrary
                item.isAudio -> Icons.Outlined.MusicNote
                else -> Icons.AutoMirrored.Outlined.InsertDriveFile
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = { Text(item.name) },
        supportingContent = {
            if (item.isDirectory) {
                Text(stringResource(R.string.webdav_folder))
            } else if (item.size > 0) {
                Text(formatBytes(item.size))
            }
        },
        trailingContent = {
            if (!item.isDirectory) {
                IconButton(onClick = onDownload) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = stringResource(R.string.webdav_download)
                    )
                }
            }
        }
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
}
