package com.vibeplayer.app.ui.services

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
    val context = LocalContext.current

    // Session / saved-password flags live outside the services DataStore, so
    // re-read them whenever this screen is shown (otherwise a card can keep
    // asking for a password that was already saved).
    LaunchedEffect(Unit) { viewModel.refresh() }

    var loginTarget by remember { mutableStateOf<ServerConfig?>(null) }
    var editTarget by remember { mutableStateOf<ServerConfig?>(null) }

    LaunchedEffect(uiState.lastLoggedInServerId) {
        if (uiState.lastLoggedInServerId != null) {
            snackbarHostState.showSnackbar(context.getString(R.string.signed_in))
            viewModel.acknowledgeLoginSuccess()
        }
    }

    // Navigation is a one-shot event produced only by an explicit service-card
    // action. A successful login alone is deliberately not enough to enter a
    // service, otherwise a retained ViewModel event can reopen Emby when the
    // user returns from Settings.
    LaunchedEffect(uiState.navigationServerId) {
        val serverId = uiState.navigationServerId ?: return@LaunchedEffect
        val item = uiState.items.firstOrNull { it.server.id == serverId }
        if (item != null) {
            when (item.server.serviceType) {
                ServiceType.EMBY,
                ServiceType.JELLYFIN -> navController.navigate(Routes.home(serverId))
                ServiceType.WEBDAV -> navController.navigate(Routes.webdavBrowse(serverId))
                ServiceType.IPTV -> navController.navigate(Routes.iptvHome(serverId))
                ServiceType.LINK -> navController.navigate(Routes.linkHome(serverId))
            }
        }
        viewModel.consumeNavigation()
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.passwordWarning) {
        if (uiState.passwordWarning) {
            snackbarHostState.showSnackbar(context.getString(R.string.save_password_failed))
            viewModel.acknowledgePasswordWarning()
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
        // The four built-in entries (Local Playback / Link Playback / Global
        // History / M3U8S) are part of the page header and therefore always
        // present, also before any external server has been added.
        ReorderableLazyColumn(
            items = uiState.items,
            key = { it.server.id },
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 16.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            header = {
                Column {
                    LocalPlaybackCard(onOpen = { navController.navigate(Routes.localHome()) })
                    Spacer(Modifier.height(12.dp))
                    LinkPlaybackCard(onOpen = {
                        // Link Playback is a built-in source entry, so it always
                        // works even without a saved Link server. Prefer an existing
                        // configured Link service, otherwise use its stable built-in id.
                        val linkId = uiState.items
                            .firstOrNull { it.server.serviceType == ServiceType.LINK }
                            ?.server?.id ?: "builtin-link-playback"
                        navController.navigate(Routes.linkHome(linkId))
                    })
                    Spacer(Modifier.height(12.dp))
                    GlobalHistoryCard(onOpen = { navController.navigate("history") })
                    Spacer(Modifier.height(12.dp))
                    M3u8sManagerCard(onOpen = { navController.navigate(Routes.tsslHome()) })
                    if (uiState.items.isEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        EmptyServicesHint(onAdd = viewModel::openAddDialog)
                    }
                }
            },
            onReorder = viewModel::reorder
        ) { item ->
            ServiceCard(
                item = item,
                onOpenClick = { viewModel.enterServer(item.server) },
                onLoginClick = { loginTarget = item.server },
                onEditClick = { editTarget = item.server },
                onRemoveClick = { viewModel.removeServer(item.server) },
                isEntering = uiState.enteringServerId == item.server.id
            )
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
private fun LinkPlaybackCard(onOpen: () -> Unit) {
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
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.services_link_playback),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.services_link_playback_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun GlobalHistoryCard(onOpen: () -> Unit) {
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
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.services_global_history),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.services_global_history_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun M3u8sManagerCard(onOpen: () -> Unit) {
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
private fun EmptyServicesHint(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.no_services),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onRemoveClick: () -> Unit,
    isEntering: Boolean
) {
    val canEnter = (item.hasSession || item.hasSavedPassword) && !isEntering
    val cardScale by animateFloatAsState(
        targetValue = if (isEntering) 0.98f else 1f,
        label = "serviceEnterScale"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(cardScale)
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
                imageVector = item.server.serviceType.pickerIcon,
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
            AnimatedVisibility(
                visible = isEntering,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .size(24.dp),
                    strokeWidth = 2.5.dp
                )
            }
            if (!isEntering && (item.hasSession || item.hasSavedPassword)) {
                TextButton(onClick = onOpenClick) {
                    Text(stringResource(R.string.open))
                }
            } else if (!isEntering) {
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
