package com.vibeplayer.app.ui.player

import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.vibeplayer.app.R
import kotlinx.coroutines.delay

@Composable
fun PlaybackSpeedNotice(settings: PlayerSettingsViewModel, modifier: Modifier = Modifier) {
    val state by settings.playerState.collectAsState()
    val notice = state.speedAdjustment ?: return
    LaunchedEffect(notice) { delay(7_000); settings.dismissSpeedAdjustment() }
    Snackbar(modifier = modifier, containerColor = Color(0xEE202124), contentColor = Color.White,
        action = {
            TextButton(onClick = settings::dismissSpeedAdjustment) {
                Text(stringResource(R.string.message_dismiss), color = Color.White)
            }
        }) {
        Text(stringResource(R.string.player_speed_adapted,
            notice.requested.toString().removeSuffix(".0"), notice.actual.toString().removeSuffix(".0")))
    }
}
