package com.vibeplayer.app.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType

/**
 * Service-type chooser for the "add server" dialog: one fixed-size, icon-only
 * tile per [ServiceType] (equal width via `weight(1f)`, identical height), with
 * the selected type spelled out in a single caption below. Text inside the tiles
 * is what made the previous segmented row render at different heights per type.
 */
@Composable
private fun ServiceTypePicker(
    selected: ServiceType,
    onSelect: (ServiceType) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = ServiceType.entries
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(shape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .selectable(
                            selected = isSelected,
                            onClick = { onSelect(option) },
                            role = Role.RadioButton
                        ),
                    content = {
                        Icon(
                            imageVector = option.pickerIcon,
                            contentDescription = option.displayName,
                            tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.Center)
                        )
                    }
                )
            }
        }
        Text(
            text = selected.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
    }
}

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onSave: (ServerForm, password: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var savePassword by remember { mutableStateOf(true) }
    var trustSelfSignedCertificate by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(ServiceType.EMBY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_server)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                ServiceTypePicker(selected = type, onSelect = { type = it })
                if (type == ServiceType.EMBY || type == ServiceType.JELLYFIN || type == ServiceType.WEBDAV) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.server_url)) },
                        placeholder = { Text("https://example.com:8096") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (type == ServiceType.EMBY || type == ServiceType.JELLYFIN || type == ServiceType.WEBDAV) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (type == ServiceType.EMBY || type == ServiceType.JELLYFIN) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = savePassword,
                                onCheckedChange = { savePassword = it }
                            )
                            Text(
                                text = stringResource(R.string.save_password_auto_enter),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    SelfSignedCertificateOption(
                        checked = trustSelfSignedCertificate,
                        onCheckedChange = { trustSelfSignedCertificate = it }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.service_enter_name_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = when (type) {
                    ServiceType.EMBY, ServiceType.JELLYFIN, ServiceType.WEBDAV ->
                        baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                    else -> name.isNotBlank()
                },
                onClick = {
                    onSave(
                        ServerForm(
                            name = name,
                            baseUrl = baseUrl,
                            username = username,
                            serviceType = type,
                            autoLogin = savePassword,
                            trustSelfSignedCertificate = trustSelfSignedCertificate
                        ),
                        password
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditServerDialog(
    server: ServerConfig,
    onDismiss: () -> Unit,
    onSave: (ServerForm, password: String, savePassword: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(server.name) }
    var baseUrl by remember { mutableStateOf(server.baseUrl) }
    var username by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf("") }
    // Start from what this server actually does today, so opening the dialog and
    // pressing Save never silently changes the user's stored-password choice.
    var savePassword by remember { mutableStateOf(server.autoLogin) }
    var trustSelfSignedCertificate by remember {
        mutableStateOf(server.trustSelfSignedCertificate)
    }

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
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.server_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isCredentialServer) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (server.serviceType == ServiceType.EMBY || server.serviceType == ServiceType.JELLYFIN) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = savePassword,
                                onCheckedChange = { savePassword = it }
                            )
                            Text(
                                text = stringResource(R.string.save_password_auto_enter),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    SelfSignedCertificateOption(
                        checked = trustSelfSignedCertificate,
                        onCheckedChange = { trustSelfSignedCertificate = it }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        ServerForm(
                            name = name,
                            baseUrl = baseUrl,
                            username = username,
                            serviceType = server.serviceType,
                            trustSelfSignedCertificate = trustSelfSignedCertificate
                        ),
                        password,
                        savePassword
                    )
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun SelfSignedCertificateOption(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
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
                            onCheckedChange = { savePassword = it }
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
            TextButton(
                enabled = password.isNotBlank(),
                onClick = { onLogin(password, savePassword) }
            ) {
                Text(stringResource(R.string.sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
