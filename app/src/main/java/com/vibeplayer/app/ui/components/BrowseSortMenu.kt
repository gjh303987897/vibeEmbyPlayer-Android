package com.vibeplayer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.model.BrowseSortOrder

@Composable
fun BrowseSortMenu(order: BrowseSortOrder, onSelect: (BrowseSortOrder) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val current = stringResource(browseSortLabel(order))
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { stateDescription = current }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, stringResource(R.string.browse_sort))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Text(stringResource(R.string.browse_sort), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
            BrowseSortOrder.entries.forEach { candidate ->
                DropdownMenuItem(text = { Text(stringResource(browseSortLabel(candidate))) },
                    onClick = { onSelect(candidate); expanded = false },
                    trailingIcon = { Box(Modifier.size(24.dp)) {
                        if (candidate == order) Icon(Icons.Outlined.Check, null)
                    } }, modifier = Modifier.semantics { selected = candidate == order })
            }
        }
    }
}

private fun browseSortLabel(order: BrowseSortOrder): Int = when (order) {
    BrowseSortOrder.NAME_ASC -> R.string.browse_sort_name_asc
    BrowseSortOrder.NAME_DESC -> R.string.browse_sort_name_desc
    BrowseSortOrder.SIZE_ASC -> R.string.browse_sort_size_asc
    BrowseSortOrder.SIZE_DESC -> R.string.browse_sort_size_desc
    BrowseSortOrder.MODIFIED_NEWEST -> R.string.browse_sort_modified_newest
    BrowseSortOrder.MODIFIED_OLDEST -> R.string.browse_sort_modified_oldest
}
