package com.vibeplayer.app.ui.services

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibeplayer.app.R
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.ui.components.AppSnackbarHost
import com.vibeplayer.app.util.normalizeUrlInput

/** Two-column chooser with visible names and radio semantics. */
@Composable
private fun ServiceTypePicker(
    selected: ServiceType,
    onSelect: (ServiceType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServiceType.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    val isSelected = option == selected
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(option.pickerIcon, contentDescription = null, modifier = Modifier.size(24.dp))
                            Text(option.displayName, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ServerAddressFields(
    scheme: String,
    onSchemeChange: (String) -> Unit,
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    serviceType: ServiceType,
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("http", "https").forEachIndexed { index, value ->
                SegmentedButton(
                    selected = scheme == value,
                    enabled = enabled,
                    onClick = { onSchemeChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(value.uppercase())
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { value ->
                    // Pasting a scheme or port is handled by the ViewModel's
                    // validation; keep the field focused on host/path input.
                    // This field is the server URL/address input, so remove
                    // accidental whitespace only at its two edges.
                    val normalized = normalizeUrlInput(value)
                    onHostChange(normalized.removePrefix("http://").removePrefix("https://"))
                },
                label = { Text(stringResource(R.string.server_host)) },
                placeholder = { Text("example.com/dav") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = port,
                onValueChange = { value -> onPortChange(value.filter(Char::isDigit).take(5)) },
                label = { Text(stringResource(R.string.server_port)) },
                placeholder = { Text(defaultServerPort(serviceType, scheme)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.width(112.dp)
            )
        }
    }
}

/**
 * Submit button for the server dialogs. While [busy] it becomes a disabled
 * progress button, so a slow save / first login is visible and repeated taps
 * cannot submit the form twice.
 */
@Composable
private fun DialogSubmitButton(
    enabled: Boolean,
    busy: Boolean,
    @StringRes busyText: Int,
    onClick: () -> Unit
) {
    TextButton(enabled = enabled && !busy, onClick = onClick) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(if (busy) busyText else R.string.save))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit,
    onSave: (ServerForm, password: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var scheme by remember { mutableStateOf("http") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(defaultServerPort(ServiceType.EMBY, "http")) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(true) }
    var trustSelfSignedCertificate by remember { mutableStateOf(false) }
    var privateMode by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(ServiceType.EMBY) }
    val needsConnection = type == ServiceType.EMBY || type == ServiceType.JELLYFIN || type == ServiceType.WEBDAV
    val validAddress = !needsConnection || buildServerBaseUrl(scheme, host, port) != null
    val canSave = if (needsConnection) validAddress && username.isNotBlank() && password.isNotBlank()
    else name.isNotBlank()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(),
                snackbarHost = { AppSnackbarHost(snackbarHostState) },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = { Text(stringResource(R.string.add_server)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss, enabled = !busy) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel))
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = onDismiss, enabled = !busy) {
                                Text(stringResource(R.string.cancel))
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                enabled = canSave && !busy,
                                onClick = {
                                    onSave(
                                        ServerForm(
                                            name = name,
                                            scheme = scheme,
                                            host = host,
                                            port = port,
                                            username = username,
                                            serviceType = type,
                                            autoLogin = savePassword,
                                            trustSelfSignedCertificate = trustSelfSignedCertificate,
                                            privateMode = privateMode
                                        ),
                                        password
                                    )
                                }
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(if (busy) R.string.services_saving else R.string.save))
                            }
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .widthIn(max = 640.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.service_add_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FormSectionTitle(R.string.service_type_section)
                        ServiceTypePicker(
                            selected = type,
                            onSelect = {
                                if (it != type) {
                                    type = it
                                    port = defaultServerPort(it, scheme)
                                }
                            },
                            enabled = !busy
                        )
                        FormSectionTitle(R.string.service_connection_section)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.server_name)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (needsConnection) {
                            Text(
                                text = stringResource(R.string.server_name_optional_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ServerAddressFields(
                                scheme = scheme,
                                onSchemeChange = { selected ->
                                    val oldDefault = defaultServerPort(type, scheme)
                                    scheme = selected
                                    if (port == oldDefault) port = defaultServerPort(type, selected)
                                },
                                host = host,
                                onHostChange = { host = it },
                                port = port,
                                onPortChange = { port = it },
                                serviceType = type,
                                enabled = !busy
                            )
                            if (host.isNotBlank() && !validAddress) {
                                Text(
                                    text = stringResource(R.string.server_address_required),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            FormSectionTitle(R.string.service_account_section)
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text(stringResource(R.string.username)) },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text(stringResource(R.string.password)) },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (type == ServiceType.EMBY || type == ServiceType.JELLYFIN) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().toggleable(
                                        value = savePassword,
                                        enabled = !busy,
                                        role = Role.Checkbox,
                                        onValueChange = { savePassword = it }
                                    ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = savePassword,
                                        onCheckedChange = null,
                                        enabled = !busy
                                    )
                                    Text(
                                        text = stringResource(R.string.save_password_auto_enter),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.service_enter_name_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FormSectionTitle(R.string.service_options_section)
                        if (needsConnection) {
                            SelfSignedCertificateOption(
                                checked = trustSelfSignedCertificate,
                                onCheckedChange = { trustSelfSignedCertificate = it },
                                enabled = !busy
                            )
                        }
                        PrivacyCardOption(
                            checked = privateMode,
                            onCheckedChange = { privateMode = it },
                            enabled = !busy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormSectionTitle(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 10.dp)
    )
}

@Composable
fun EditServerDialog(
    server: ServerConfig,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (ServerForm, password: String, savePassword: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    val initialAddress = remember(server.baseUrl) { parseServerAddress(server.baseUrl) }
    var scheme by remember(server.baseUrl) { mutableStateOf(initialAddress.scheme) }
    var host by remember(server.baseUrl) { mutableStateOf(initialAddress.host) }
    var port by remember(server.baseUrl) { mutableStateOf(initialAddress.port) }
    var username by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf("") }
    // Start from what this server actually does today, so opening the dialog and
    // pressing Save never silently changes the user's stored-password choice.
    var savePassword by remember { mutableStateOf(server.autoLogin) }
    var trustSelfSignedCertificate by remember {
        mutableStateOf(server.trustSelfSignedCertificate)
    }
    var privateMode by remember { mutableStateOf(server.privateMode) }

    val isCredentialServer = server.serviceType == ServiceType.EMBY ||
        server.serviceType == ServiceType.JELLYFIN ||
        server.serviceType == ServiceType.WEBDAV

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_server)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = server.serviceType.pickerIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = server.serviceType.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                if (isCredentialServer) {
                    ServerAddressFields(
                        scheme = scheme,
                        onSchemeChange = { scheme = it },
                        host = host,
                        onHostChange = { host = it },
                        port = port,
                        onPortChange = { port = it },
                        serviceType = server.serviceType,
                        enabled = !busy
                    )
                }
                PrivacyCardOption(checked = privateMode, onCheckedChange = { privateMode = it }, enabled = !busy)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isCredentialServer) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (server.serviceType == ServiceType.EMBY || server.serviceType == ServiceType.JELLYFIN) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = savePassword,
                                onCheckedChange = { savePassword = it },
                                enabled = !busy
                            )
                            Text(
                                text = stringResource(R.string.save_password_auto_enter),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    SelfSignedCertificateOption(
                        checked = trustSelfSignedCertificate,
                        onCheckedChange = { trustSelfSignedCertificate = it },
                        enabled = !busy
                    )
                }
            }
        },
        confirmButton = {
            DialogSubmitButton(
                busy = busy,
                busyText = R.string.services_saving,
                // Same client-side check as the add dialog, so a cleared address
                // never submits (and therefore never closes) the form.
                enabled = !isCredentialServer || (host.isNotBlank() && port.isNotBlank()),
                onClick = {
                    onSave(
                        ServerForm(
                            name = name,
                            scheme = scheme,
                            host = host,
                            port = port,
                            username = username,
                            serviceType = server.serviceType,
                            trustSelfSignedCertificate = trustSelfSignedCertificate,
                            privateMode = privateMode
                        ),
                        password,
                        savePassword
                    )
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun PrivacyCardOption(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(stringResource(R.string.service_private_card), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(R.string.service_private_card_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 48.dp)
        )
    }
}

@Composable
private fun SelfSignedCertificateOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            Text(
                text = stringResource(R.string.trust_self_signed_certificate),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = stringResource(R.string.trust_self_signed_certificate_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 48.dp)
        )
    }
}

@Composable
fun LoginDialog(
    server: ServerConfig,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLogin: (password: String, savePassword: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(server.autoLogin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = server.serviceType.pickerIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = server.username,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (server.serviceType == ServiceType.EMBY || server.serviceType == ServiceType.JELLYFIN) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = savePassword,
                            onCheckedChange = { savePassword = it },
                            enabled = !busy
                        )
                        Text(
                            text = stringResource(R.string.save_password_auto_enter),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            DialogSubmitButton(
                busy = busy,
                busyText = R.string.services_signing_in,
                enabled = password.isNotBlank(),
                onClick = { onLogin(password, savePassword) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
