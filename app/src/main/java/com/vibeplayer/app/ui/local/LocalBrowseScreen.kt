package com.vibeplayer.app.ui.local

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.model.LocalMediaItem
import com.vibeplayer.app.ui.navigation.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalBrowseScreen(
    navController: NavController,
    viewModel: LocalBrowseViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.load() }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Persist the tree grant so the folder stays accessible across
            // app restarts (without this the grant is lost when the process dies).
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            val name = try {
                com.vibeplayer.app.util.SafNames.displayName(context, uri) ?: "Local folder"
            } catch (_: Exception) {
                "Local folder"
            }
            viewModel.addRoot(uri.toString(), name)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add folder")
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.directoryName) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.browsingDirectory) {
                                if (state.stack.size <= 1) viewModel.backToRoots() else viewModel.goUp()
                            } else {
                                navController.popBackStack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Go back")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        if (state.browsingDirectory) {
            val browseError = state.error
            when {
                state.loading && state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { CircularProgressIndicator() }
                }
                browseError != null && state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = browseError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                state.items.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.local_no_media),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Inset the list with the Scaffold paddings: the app draws
                        // edge-to-edge (enableEdgeToEdge), so without the top inset the
                        // first rows are rendered *behind* the top bar / status bar and
                        // look cut off at the top. The bottom keeps room for the
                        // "Add folder" FAB and the navigation bar.
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            bottom = innerPadding.calculateBottomPadding() + 96.dp
                        )
                    ) {
                        items(state.items, key = { it.uri }) { item ->
                            LocalItemRow(
                                item = item,
                                onOpen = {
                                    if (item.isDirectory) viewModel.navigateInto(item)
                                    else navController.navigate(Routes.localPlayer(item.uri))
                                }
                            )
                        }
                    }
                }
            }
        } else {
            RootsList(
                state = state,
                topPadding = innerPadding.calculateTopPadding(),
                bottomPadding = innerPadding.calculateBottomPadding(),
                onOpen = viewModel::openRoot,
                onRemove = viewModel::removeRoot
            )
        }
    }
}

@Composable
private fun RootsList(
    state: LocalBrowseUiState,
    topPadding: Dp,
    bottomPadding: Dp,
    onOpen: (LocalMediaRootEntity) -> Unit,
    onRemove: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Same rule as above: reserve the top-bar / system-bar inset via
        // contentPadding, otherwise the folder cards stick out over the top of the
        // visible area ("文件夹在顶部超出显示区域").
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topPadding + 16.dp,
            bottom = bottomPadding + 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.local_add_folder_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.roots.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.local_no_folders),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(state.roots, key = { it.id }) { root ->
            RootRow(root = root, onOpen = { onOpen(root) }, onRemove = { onRemove(root.id) })
        }
    }
}

@Composable
private fun RootRow(root: LocalMediaRootEntity, onOpen: () -> Unit, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(root.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (root.available) root.path else stringResource(R.string.local_pick_again),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (root.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun LocalItemRow(item: LocalMediaItem, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.isDirectory) Icons.Outlined.Folder else Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!item.isDirectory) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Play")
            }
        }
    }
}
