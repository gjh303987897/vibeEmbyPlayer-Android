package com.vibeplayer.app.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibeplayer.app.R
import com.vibeplayer.app.domain.subtitle.SubtitleImportError
import com.vibeplayer.app.ui.player.ManualSubtitleViewModel
import com.vibeplayer.app.ui.player.LocalPlayerPip

@Composable
fun ManualSubtitleImportAction(modifier: Modifier = Modifier, viewModel: ManualSubtitleViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingSession by rememberSaveable { mutableLongStateOf(-1L) }
    val pip = LocalPlayerPip.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pip?.setExternalUiVisible(false)
        if (uri != null) viewModel.load(uri, pendingSession)
    }
    Column(modifier) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        TextButton(enabled = !state.loading, onClick = {
            pendingSession = viewModel.captureSession()
            pip?.setExternalUiVisible(true)
            picker.launch(arrayOf("*/*"))
        }) { Text(stringResource(R.string.subtitle_load_phone)) }
        Text(stringResource(R.string.subtitle_import_help), style = MaterialTheme.typography.bodySmall)
        if (state.loading) Row {
            CircularProgressIndicator(Modifier.size(24.dp))
            TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) }
        }
        state.error?.let { error ->
            Text(stringResource(when (error) {
                SubtitleImportError.TOO_LARGE -> R.string.subtitle_error_large
                SubtitleImportError.UNSUPPORTED_FORMAT -> R.string.subtitle_error_format
                SubtitleImportError.UNSUPPORTED_ENCODING -> R.string.subtitle_error_encoding
                SubtitleImportError.INVALID_CONTENT -> R.string.subtitle_error_content
                SubtitleImportError.NO_PLAYBACK -> R.string.subtitle_error_no_playback
                SubtitleImportError.SESSION_CHANGED -> R.string.subtitle_error_session
                SubtitleImportError.UNREADABLE -> R.string.subtitle_error_read
            }), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
