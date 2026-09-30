package com.vibeplayer.app.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R

/** Keeps the video playing while blocking player touches until the viewer unlocks it. */
@Composable
fun PlayerScreenLock(
    locked: Boolean,
    controlsVisible: Boolean,
    onToggleLock: () -> Unit
) {
    BackHandler(enabled = locked) { /* Ignore system back while the player is locked. */ }

    Box(Modifier.fillMaxSize()) {
        if (locked) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            ).consume()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
            )
        }

        if (locked || controlsVisible) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    imageVector = if (locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = stringResource(
                        if (locked) R.string.player_unlock else R.string.player_lock
                    ),
                    tint = Color.White
                )
            }
        }
    }
}
