package com.vibeplayer.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import com.vibeplayer.app.player.PictureInPictureController
import com.vibeplayer.app.player.PipEntryBlock
import com.vibeplayer.app.player.PlayerPipState
import kotlinx.coroutines.flow.MutableStateFlow

val LocalPlayerPip = staticCompositionLocalOf<PictureInPictureController?> { null }

@Composable
fun rememberPlayerPipState(): PlayerPipState {
    val controller = LocalPlayerPip.current
    val fallback = remember { MutableStateFlow(PlayerPipState()) }
    return (controller?.state ?: fallback).collectAsState().value
}

/** Register only actual playback destinations, never library/detail screens. */
@Composable
fun rememberPlayerPictureInPictureState(): PlayerPipState {
    val controller = LocalPlayerPip.current
    DisposableEffect(controller) {
        val owner = Any()
        controller?.attachPlayer(owner)
        onDispose { controller?.detachPlayer(owner) }
    }
    return rememberPlayerPipState()
}

@Composable
internal fun PipAutoEntryBlocker(visible: Boolean) {
    val controller = LocalPlayerPip.current
    DisposableEffect(controller, visible) {
        val token = if (visible) controller?.addAutoEntryBlock() else null
        onDispose { if (token != null) controller?.removeAutoEntryBlock(token) }
    }
}

@Composable
internal fun PlayerPipAction() {
    val controller = LocalPlayerPip.current
    val state = rememberPlayerPipState()
    if (state.supported) IconButton(onClick = { controller?.enter() }, enabled = state.canEnter) {
        Icon(Icons.Outlined.PictureInPictureAlt, stringResource(R.string.pip_enter),
            tint = Color.White.copy(alpha = if (state.canEnter) 1f else .38f))
    }
}

private fun pipReasonLabel(reason: PipEntryBlock): Int = when (reason) {
    PipEntryBlock.NONE -> R.string.pip_help
    PipEntryBlock.UNSUPPORTED -> R.string.pip_unsupported
    PipEntryBlock.PERMISSION -> R.string.pip_permission
    PipEntryBlock.PRIVATE_VIDEO -> R.string.pip_private
    PipEntryBlock.NOT_PLAYING -> R.string.pip_not_playing
}

@Composable
internal fun PictureInPictureSettings(automatic: Boolean, onAutomaticChange: (Boolean) -> Unit) {
    val controller = LocalPlayerPip.current
    val state = rememberPlayerPipState()
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(pipReasonLabel(state.entryBlock)))
        Button(onClick = { controller?.enter() }, enabled = state.canEnter) { Text(stringResource(R.string.pip_enter)) }
        val automaticLabel = stringResource(R.string.pip_automatic)
        ListItem(headlineContent = { Text(automaticLabel) },
            supportingContent = { Text(stringResource(R.string.pip_automatic_help)) },
            trailingContent = { Switch(automatic, onCheckedChange = onAutomaticChange, enabled = state.supported,
                modifier = Modifier.semantics { contentDescription = automaticLabel }) })
        if (state.supported) TextButton(onClick = { controller?.openSystemSettings() }) {
            Text(stringResource(R.string.pip_system_settings))
        }
    }
}
