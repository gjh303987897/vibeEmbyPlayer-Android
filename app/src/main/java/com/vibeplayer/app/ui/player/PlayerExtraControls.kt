package com.vibeplayer.app.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.vibeplayer.app.R
import com.vibeplayer.app.player.AudioTrack

/** Applies Android immersive mode while a player screen's fullscreen toggle is active. */
@Composable
fun PlayerFullscreenEffect(fullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity, fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            // Video fullscreen is intentionally landscape. Resetting to
            // UNSPECIFIED on exit gives the user's automatic rotation setting
            // control again.
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // Navigation away must never leave the rest of the app in immersive mode.
            controller.show(WindowInsetsCompat.Type.systemBars())
            if (fullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}

/** Shared fullscreen and audio-track actions used by every video source. */
@Composable
fun PlayerExtraActions(
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    audioTracks: List<AudioTrack>,
    selectedAudioTrackKey: String?,
    onAudioTrackSelected: (AudioTrack) -> Unit
) {
    Row {
        if (audioTracks.size > 1) {
            AudioTrackSelector(audioTracks, selectedAudioTrackKey, onAudioTrackSelected)
        }
        IconButton(onClick = onToggleFullscreen) {
            Icon(
                imageVector = if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                contentDescription = stringResource(
                    if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen
                ),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun AudioTrackSelector(
    tracks: List<AudioTrack>,
    selectedKey: String?,
    onSelect: (AudioTrack) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            Icons.Outlined.Audiotrack,
            contentDescription = stringResource(R.string.player_audio_tracks),
            tint = Color.White
        )
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.player_audio_tracks)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    tracks.forEach { track ->
                        TextButton(
                            onClick = {
                                onSelect(track)
                                expanded = false
                            }
                        ) {
                            Text(
                                if (track.key == selectedKey) "✓ ${track.label}" else track.label
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { expanded = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
