package com.vibeplayer.app.ui.services

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vibeplayer.app.R
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.ui.navigation.Routes
import kotlinx.coroutines.launch

/**
 * Services home screen: lists configured media service accounts (Emby /
 * Jellyfin) and lets the user add, sign in to, and remove them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    navController: NavController,
    viewModel: ServicesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loginTarget by remember { mutableStateOf<ServerConfig?>(null) }
    var editTarget by remember { mutableStateOf<ServerConfig?>(null) }

    LaunchedEffect(uiState.lastLoggedInServerId) {
        uiState.lastLoggedInServerId?.let { serverId ->
            snackbarHostState.showSnackbar(context.getString(R.string.signed_in))
            // On a successful login the server session is now usable, so open the
            // server home (previously the UI only showed a snackbar and stayed put).
            uiState.items.firstOrNull { it.server.id == serverId }?.let { item ->
                when (item.server.serviceType) {
                    ServiceType.EMBY,
                    ServiceType.JELLYFIN -> navController.navigate(Routes.home(serverId))
                    ServiceType.WEBDAV -> navController.navigate(Routes.webdavBrowse(serverId))
                    ServiceType.IPTV -> navController.navigate(Routes.iptvHome(serverId))
                    ServiceType.LINK -> navController.navigate(Routes.linkHome(serverId))
                }
            }
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.nav_services)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAddDialog) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add_server))
            }
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
            uiState.items.isEmpty() -> {
                EmptyServices(
                    onAdd = viewModel::openAddDialog,
                    modifier = Modifier.fillMaxSize().padding(innerPadding)
                )
            }
            else -> {
                ReorderableLazyColumn(
                    items = uiState.items,
                    key = { it.server.id },
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 16.dp,
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    header = {
                        Column {
                            LocalPlaybackCard(onOpen = { navController.navigate(Routes.localHome()) })
                            Spacer(Modifier.height(12.dp))
                            TsslManagerCard(onOpen = { navController.navigate(Routes.tsslHome()) })
                        }
                    },
                    onReorder = viewModel::reorder
                ) { item ->
                    ServiceCard(
                        item = item,
                        onOpenClick = {
                            // One-tap entry: an existing session (or a saved password
                            // auto-login driven by lastLoggedInServerId) enters the server.
                            if (viewModel.enterServer(item.server)) {
                                when (item.server.serviceType) {
                                    ServiceType.EMBY,
                                    ServiceType.JELLYFIN -> navController.navigate(Routes.home(item.server.id))
                                    ServiceType.WEBDAV -> navController.navigate(Routes.webdavBrowse(item.server.id))
                                    ServiceType.IPTV -> navController.navigate(Routes.iptvHome(item.server.id))
                                    ServiceType.LINK -> navController.navigate(Routes.linkHome(item.server.id))
                                }
                            }
                        },
                        onLoginClick = { loginTarget = item.server },
                        onEditClick = { editTarget = item.server },
                        onRemoveClick = { viewModel.removeServer(item.server) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = viewModel::dismissAddDialog,
            onSave = { form, password -> viewModel.addServer(form, password) }
        )
    }

    loginTarget?.let { server ->
        LoginDialog(
            server = server,
            onDismiss = { loginTarget = null },
            onLogin = { password, savePassword ->
                loginTarget = null
                viewModel.loginServer(server, password, savePassword)
            }
        )
    }

    editTarget?.let { server ->
        EditServerDialog(
            server = server,
            onDismiss = { editTarget = null },
            onSave = { form, password, savePassword ->
                editTarget = null
                viewModel.editServer(server, form, password, savePassword)
            }
        )
    }
}

@Composable
private fun LocalPlaybackCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.VideoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.services_local_playback),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.services_local_playback_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TsslManagerCard(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.services_tssl),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.services_tssl_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyServices(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.no_services),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(16.dp))
        TextButton(onClick = onAdd) {
            Text(stringResource(R.string.add_server))
        }
    }
}

@Composable
private fun ServiceCard(
    item: ServiceItemUi,
    onOpenClick: () -> Unit,
    onLoginClick: () -> Unit,
    onEditClick: () -> Unit,
    onRemoveClick: () -> Unit
) {
    val canEnter = item.hasSession || item.hasSavedPassword
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canEnter, onClick = onOpenClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Cloud,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.server.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${item.server.username} · ${item.server.serviceType.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = item.server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.hasSession || item.hasSavedPassword) {
                TextButton(onClick = onOpenClick) {
                    Text(stringResource(R.string.open))
                }
            } else {
                IconButton(onClick = onLoginClick) {
                    Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = stringResource(R.string.sign_in))
                }
            }
            IconButton(onClick = onEditClick) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.edit_server))
            }
            IconButton(onClick = onRemoveClick) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove))
            }
        }
    }
}
