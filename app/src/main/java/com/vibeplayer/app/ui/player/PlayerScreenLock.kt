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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vibeplayer.app.R
import kotlinx.coroutines.delay

private const val LOCK_ICON_IDLE_TIMEOUT_MS = 3_000L

/** Keeps the video playing while blocking player touches until the viewer unlocks it. */
@Composable
fun PlayerScreenLock(
    locked: Boolean,
    controlsVisible: Boolean,
    onToggleLock: () -> Unit
) {
    var lockIconVisible by remember(locked) { mutableStateOf(true) }
    var touching by remember(locked) { mutableStateOf(false) }
    var interaction by remember(locked) { mutableIntStateOf(0) }
    LaunchedEffect(locked, touching, interaction) {
        if (locked && !touching) {
            delay(LOCK_ICON_IDLE_TIMEOUT_MS)
            lockIconVisible = false
        }
    }
    BackHandler(enabled = locked) { /* Ignore system back while the player is locked. */ }

    Box(Modifier.fillMaxSize().then(if (locked) Modifier.pointerInput(Unit) {
        // Observe both the blocking surface and the unlock button without stealing its click.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                touching = event.changes.any { it.pressed }
                if (touching) lockIconVisible = true
                if (event.changes.any { it.pressed != it.previousPressed }) interaction++
            }
        }
    } else Modifier)) {
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

        if (if (locked) lockIconVisible else controlsVisible) {
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
