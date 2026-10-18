package com.vibeplayer.app.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.SubtitleTrack
import com.vibeplayer.app.ui.components.ManualSubtitleImportAction

@Composable
internal fun SubtitleSelector(tracks: List<SubtitleTrack>, selectedKey: String?, onSelect: (SubtitleTrack?) -> Unit, compact: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    if (compact) IconButton(onClick = { expanded = true }) {
        Icon(Icons.Outlined.Subtitles, stringResource(R.string.subtitle_title), tint = Color.White)
    } else TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.subtitle_title)) }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text(stringResource(R.string.subtitle_title)) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                SubtitleChoice(stringResource(R.string.subtitle_off), selectedKey == null) { onSelect(null); expanded = false }
                tracks.forEach { track ->
                    SubtitleChoice(track.label, track.key == selectedKey) { onSelect(track); expanded = false }
                }
                ManualSubtitleImportAction()
            }
        },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.customization_close)) } }
    )
}

@Composable
private fun SubtitleChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}
