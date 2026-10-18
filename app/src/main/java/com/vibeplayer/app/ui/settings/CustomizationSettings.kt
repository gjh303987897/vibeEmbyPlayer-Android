package com.vibeplayer.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.domain.history.HistoryRetentionPolicy
import com.vibeplayer.app.model.HomeLayout
import com.vibeplayer.app.model.PlayerLayout
import com.vibeplayer.app.model.normalizeServiceCardScale

@Composable
internal fun CustomizationSettings(state: SettingsUiState, viewModel: SettingsViewModel) {
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var retentionOpen by rememberSaveable { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.customization_appearance)) },
        supportingContent = { Text(stringResource(R.string.customization_appearance_summary)) },
        modifier = Modifier.clickable { appearanceOpen = true }
    )
    Text(stringResource(R.string.nav_history), style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    ListItem(
        headlineContent = { Text(stringResource(R.string.history_retention)) },
        supportingContent = { Text(pluralStringResource(R.plurals.history_retention_days, state.historyRetentionDays, state.historyRetentionDays)) },
        trailingContent = { if (state.retentionSaving) CircularProgressIndicator(Modifier.size(24.dp)) },
        modifier = Modifier.clickable(enabled = !state.retentionSaving) { retentionOpen = true }
    )
    if (state.customizationSaveFailed) Text(stringResource(R.string.customization_save_failed),
        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    if (appearanceOpen) AppearanceDialog(state, viewModel) { appearanceOpen = false }
    if (retentionOpen) RetentionDialog(state.historyRetentionDays,
        onSave = { viewModel.setHistoryRetentionDays(it); retentionOpen = false },
        onDismiss = { retentionOpen = false })
}

@Composable
private fun AppearanceDialog(state: SettingsUiState, viewModel: SettingsViewModel, onDismiss: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val prefs = state.appearance
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.customization_appearance)) },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    listOf(R.string.appearance_home, R.string.appearance_player, R.string.appearance_cards).forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                    }
                }
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    when (tab) {
                        0 -> {
                            Text("Emby", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                            LayoutChoices(prefs.embyHomeLayout == HomeLayout.TRENDY) {
                                viewModel.setHomeLayout(false, if (it) HomeLayout.TRENDY else HomeLayout.TRADITIONAL)
                            }
                            Text("Jellyfin", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                            LayoutChoices(prefs.jellyfinHomeLayout == HomeLayout.TRENDY) {
                                viewModel.setHomeLayout(true, if (it) HomeLayout.TRENDY else HomeLayout.TRADITIONAL)
                            }
                        }
                        1 -> {
                            LayoutChoices(prefs.playerLayout == PlayerLayout.TRENDY) {
                                viewModel.setPlayerLayout(if (it) PlayerLayout.TRENDY else PlayerLayout.TRADITIONAL)
                            }
                            Text(stringResource(R.string.appearance_player_help), style = MaterialTheme.typography.bodySmall)
                        }
                        else -> {
                            var scale by remember(prefs.serviceCardScalePercent) { mutableFloatStateOf(prefs.serviceCardScalePercent.toFloat()) }
                            Text("${normalizeServiceCardScale(scale.toInt())}%", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
                            Slider(value = scale, onValueChange = { scale = it }, valueRange = 50f..150f, steps = 19,
                                onValueChangeFinished = { viewModel.setServiceCardScale(scale.toInt()) })
                            Text(stringResource(R.string.appearance_cards_help), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.customizationSaveFailed) Text(stringResource(R.string.customization_save_failed),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.customization_close)) } }
    )
}

@Composable
internal fun LayoutChoices(trendy: Boolean, onSelect: (Boolean) -> Unit) {
    listOf(true to R.string.appearance_trendy, false to R.string.appearance_traditional).forEach { (value, label) ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(trendy == value, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = trendy == value, onClick = null)
            Text(stringResource(label), Modifier.padding(start = 12.dp))
        }
    }
}

@Composable
private fun RetentionDialog(days: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(days) }
    var custom by rememberSaveable { mutableStateOf(days.toString()) }
    val presets = listOf(7, 14, 30, 60, 90, 180, 365)
    var useCustom by rememberSaveable { mutableStateOf(days !in presets) }
    val customDays = custom.toIntOrNull()?.takeIf { it in HistoryRetentionPolicy.MIN_DAYS..HistoryRetentionPolicy.MAX_DAYS }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_retention)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.history_retention_help), style = MaterialTheme.typography.bodySmall)
                presets.forEach { value ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(!useCustom && selected == value,
                        role = Role.RadioButton, onClick = { selected = value; useCustom = false }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !useCustom && selected == value, onClick = null)
                        Text(pluralStringResource(R.plurals.history_retention_days, value, value))
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(useCustom, role = Role.RadioButton, onClick = { useCustom = true }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(useCustom, onClick = null)
                    Text(stringResource(R.string.history_retention_custom))
                }
                if (useCustom) OutlinedTextField(value = custom, onValueChange = { custom = it.take(4) },
                    label = { Text(stringResource(R.string.history_retention_range)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = customDays == null)
            }
        },
        confirmButton = { TextButton(enabled = !useCustom || customDays != null,
            onClick = { onSave(if (useCustom) requireNotNull(customDays) else selected) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}
