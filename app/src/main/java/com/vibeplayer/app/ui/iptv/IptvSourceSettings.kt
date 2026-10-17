package com.vibeplayer.app.ui.iptv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.IptvRoute
import com.vibeplayer.app.model.IptvRouteAvailability
import java.util.Locale

@Composable
internal fun IptvQualityChoices(preference: IptvQualityPreference, onChange: (IptvQualityPreference) -> Unit) {
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        IptvQualityPreference.entries.forEach { option ->
            SourceRadioRow(stringResource(qualityLabel(option)), preference == option, onClick = { onChange(option) })
        }
    }
}

@Composable
internal fun IptvSourceSettings(
    state: IptvRoutesState,
    preference: IptvQualityPreference,
    onPreferenceChange: (IptvQualityPreference) -> Unit,
    onAutomatic: () -> Unit,
    onSource: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = page) {
            listOf(R.string.iptv_sources_tab, R.string.iptv_quality_tab).forEachIndexed { index, label ->
                Tab(selected = page == index, onClick = { page = index }, text = { Text(stringResource(label)) })
            }
        }
        // The sheet supplies a bounded viewport. Large lists only compose visible routes.
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
            if (page == 1) {
                item {
                    Text(stringResource(R.string.iptv_quality_title), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    IptvQualityChoices(preference, onPreferenceChange)
                    Text(stringResource(R.string.iptv_quality_apply_help), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Text(stringResource(R.string.iptv_quality_help), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            } else {
                item {
                    if (state.checking) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.iptv_source_checking), modifier = Modifier.padding(16.dp))
                    }
                }
                item {
                    SourceRadioRow(stringResource(R.string.iptv_source_automatic), state.automatic,
                        supporting = stringResource(qualityLabel(preference)), enabled = state.routes.isNotEmpty(), onClick = onAutomatic)
                }
                items(state.routes, key = { it.key }) { route ->
                    val current = route.key == state.currentKey
                    val details = buildList {
                        add(routeQuality(route))
                        if (current) add(stringResource(R.string.iptv_source_active))
                        if (route.availability == IptvRouteAvailability.UNAVAILABLE) add(stringResource(R.string.iptv_source_unavailable))
                    }.joinToString(" · ")
                    SourceRadioRow(stringResource(R.string.iptv_source_line, route.sourceNumber),
                        selected = !state.automatic && current, supporting = details, onClick = { onSource(route.key) })
                }
                item {
                    TextButton(onClick = onRefresh, enabled = !state.checking, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.iptv_source_refresh))
                    }
                    Text(stringResource(R.string.iptv_source_switch_help), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
internal fun iptvSourceSummary(state: IptvRoutesState): String {
    val current = state.routes.firstOrNull { it.key == state.currentKey }
    val label = current?.let { stringResource(R.string.iptv_source_line, it.sourceNumber) + " · " + routeQuality(it) }
        ?: stringResource(if (state.checking) R.string.iptv_source_checking else R.string.iptv_source_automatic)
    return if (state.automatic && current != null) stringResource(R.string.iptv_source_auto_summary, label) else label
}

@Composable
private fun routeQuality(route: IptvRoute): String = buildList {
    if (route.pixels > 0) add("${route.width} × ${route.height}")
    if (route.bitrate > 0) add(String.format(Locale.getDefault(), "%.2f Mbps", route.bitrate / 1_000_000.0))
    if (isEmpty()) add(stringResource(R.string.iptv_source_quality_unknown))
}.joinToString(" · ")

@Composable
private fun SourceRadioRow(
    title: String,
    selected: Boolean,
    supporting: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun qualityLabel(preference: IptvQualityPreference) = when (preference) {
    IptvQualityPreference.HIGHEST -> R.string.iptv_quality_highest
    IptvQualityPreference.LOWEST -> R.string.iptv_quality_lowest
}
