package com.vibeplayer.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vibeplayer.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showSetPin by remember { mutableStateOf(false) }
    var showEnterPin by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(stringResource(R.string.settings_appearance))

            ThemeSection(state, viewModel)
            LanguageSection(state, viewModel)

            ToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_sub),
                checked = state.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor
            )

            ToggleRow(
                title = stringResource(R.string.settings_page_transitions),
                checked = state.pageTransitions,
                onCheckedChange = viewModel::setPageTransitions
            )

            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.settings_privacy))

            PrivacySection(
                state = state,
                onSetPin = { showSetPin = true },
                onEnterPrivacy = { showEnterPin = true },
                onExitPrivacy = { viewModel.exitPrivacy() }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSetPin) {
        PinDialog(
            title = stringResource(R.string.settings_set_pin_dialog),
            onConfirm = { pin ->
                if (viewModel.setPin(pin)) {
                    showSetPin = false
                    true
                } else {
                    false
                }
            },
            onDismiss = { showSetPin = false }
        )
    }

    if (showEnterPin) {
        PinDialog(
            title = stringResource(R.string.settings_enter_pin_dialog),
            onConfirm = { pin ->
                if (viewModel.openPrivacy(pin)) {
                    showEnterPin = false
                    true
                } else {
                    false
                }
            },
            onDismiss = { showEnterPin = false }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ThemeSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            val options = listOf(
                Triple("system", R.string.settings_theme_system, 0),
                Triple("light", R.string.settings_theme_light, 1),
                Triple("dark", R.string.settings_theme_dark, 2)
            )
            options.forEach { (value, labelRes, index) ->
                SegmentedButton(
                    selected = state.themeMode == value,
                    onClick = { viewModel.setThemeMode(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun LanguageSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            val options = listOf(
                Triple("system", R.string.settings_language_system, 0),
                Triple("en_US", R.string.settings_language_english, 1),
                Triple("zh_CN", R.string.settings_language_chinese, 2)
            )
            options.forEach { (value, labelRes, index) ->
                SegmentedButton(
                    selected = state.language == value,
                    onClick = {
                        viewModel.setLanguage(value)
                        // Recreate the activity so the changed locale applies to
                        // the whole UI immediately.
                        if (state.language != value) {
                            context.findActivity()?.recreate()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PrivacySection(
    state: SettingsUiState,
    onSetPin: () -> Unit,
    onEnterPrivacy: () -> Unit,
    onExitPrivacy: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.settings_privacy_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSetPin, modifier = Modifier.weight(1f)) {
                Text(
                    if (state.pinConfigured) stringResource(R.string.settings_change_pin)
                    else stringResource(R.string.settings_set_pin)
                )
            }
            Button(
                onClick = if (state.privacyActive) onExitPrivacy else onEnterPrivacy,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (state.privacyActive) stringResource(R.string.settings_exit_privacy)
                    else stringResource(R.string.settings_enter_privacy)
                )
            }
        }

        if (state.privacyActive) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_privacy_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PinDialog(
    title: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it; error = false },
                label = { Text(stringResource(R.string.settings_pin_placeholder)) },
                singleLine = true,
                isError = error,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (!onConfirm(pin)) error = true }
            ) { Text(stringResource(R.string.settings_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Walk the wrapped-context chain to find the host Activity (if any). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
