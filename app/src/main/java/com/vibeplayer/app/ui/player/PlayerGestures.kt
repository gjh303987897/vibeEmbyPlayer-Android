package com.vibeplayer.app.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Playback speed used while the video surface is held down. */
const val PLAYER_GESTURE_SPEED = 2f

/** What the video-surface gesture layer recognised. */
enum class PlayerGestureKind { Brightness, Volume, Speed }

/**
 * Current gesture feedback: null while idle. Rendered by [PlayerGestureOverlay];
 * the gesture modifier itself must not draw, or publishing a new value would
 * restart the pointer loop in the middle of the gesture.
 */
class PlayerGestureState {
    var indicator: PlayerGestureIndicator? by mutableStateOf(null)
        private set

    internal fun show(kind: PlayerGestureKind, value: Float) {
        indicator = PlayerGestureIndicator(kind, value)
    }

    internal fun hide() {
        indicator = null
    }
}

data class PlayerGestureIndicator(
    val kind: PlayerGestureKind,
    /** 0..1 for brightness / volume, the effective speed for a long press. */
    val value: Float
) {
    val icon: ImageVector
        get() = when (kind) {
            PlayerGestureKind.Brightness -> Icons.Outlined.Brightness6
            PlayerGestureKind.Volume -> Icons.AutoMirrored.Outlined.VolumeUp
            PlayerGestureKind.Speed -> Icons.Outlined.FastForward
        }

    /** Brightness and volume get a bar; a speed-up shows its multiplier instead. */
    val progress: Float?
        get() = if (kind == PlayerGestureKind.Speed) null else value

    val valueLabel: String
        get() = if (kind == PlayerGestureKind.Speed) {
            val digits = if (value % 1f == 0f) value.toInt().toString() else value.toString()
            "${digits}x"
        } else {
            "${(value * 100).roundToInt()}%"
        }
}

@Composable
fun rememberPlayerGestureState(): PlayerGestureState = remember { PlayerGestureState() }

/**
 * The gestures a viewer expects over the video:
 *
 * - single tap toggles the control overlay (tapping again brings it back);
 * - double tap anywhere pauses / resumes;
 * - long press plays at [speedWhilePressed] until released;
 * - vertical drag on the left half sets screen brightness, on the right half the
 *   player volume.
 *
 * Apply it to a transparent layer that sits *under* the control overlays: Compose
 * delivers pointer events to the topmost hit node first, and this loop gives up as
 * soon as a change is consumed, so the buttons, the scrubber and the top bar keep
 * the touches they need. A gesture is only claimed once it is unambiguous - past the
 * touch slop, or past the long-press timeout - which is why a single tap is reported
 * one double-tap window late: until then a tap and the first half of a double tap
 * cannot be told apart.
 *
 * Brightness is applied to this app's window (the only mechanism available to a
 * third-party app) and handed back to the system when the player goes away, so no
 * other screen is left dimmed.
 */
@Composable
fun Modifier.playerGestureSurface(
    gesture: PlayerGestureState,
    onToggleControls: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onLongPressSpeedStart: (Float) -> Unit,
    onLongPressSpeedEnd: () -> Unit,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    speedWhilePressed: Float = PLAYER_GESTURE_SPEED
): Modifier {
    val context = LocalContext.current
    val view = LocalView.current
    val brightness = remember(context) { PlayerBrightnessController(context) }
    // The pointer loop outlives recomposition, so it has to call the latest lambdas.
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentTogglePlayPause by rememberUpdatedState(onTogglePlayPause)
    val currentSpeedStart by rememberUpdatedState(onLongPressSpeedStart)
    val currentSpeedEnd by rememberUpdatedState(onLongPressSpeedEnd)
    val currentSpeedWhilePressed by rememberUpdatedState(speedWhilePressed)
    val currentVolumeChange by rememberUpdatedState(onVolumeChange)
    val currentVolume by rememberUpdatedState(volume)
    DisposableEffect(brightness) {
        // Leaving the player mid-gesture must not leave the window dimmed, and a
        // long press that was still held when the screen went away must not leave
        // playback sped up (the pointer loop is cancelled before it can undo it).
        onDispose {
            brightness.restore()
            currentSpeedEnd()
            gesture.hide()
        }
    }

    return this.pointerInput(Unit) {
        // ViewConfiguration.touchSlop is already reported in pixels.
        val slopPx = viewConfiguration.touchSlop
        awaitEachGesture {
            // Final pass = observe the event after every other node did, so a
            // control that claimed the touch makes this loop give up instead of
            // acting on the same touch a second time.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.isConsumed) {
                // The press already belongs to a control; don't run a gesture that
                // would only fire its long-press timeout while a button is held.
                return@awaitEachGesture
            }
            val pointerId: PointerId = down.id
            var axis: PlayerGestureKind? = null
            var speedBoosted = false
            var dragOrigin: Offset? = null
            var dragInitialValue = 0f
            var done = false
            while (!done) {
                // While nothing is decided the long-press timeout is the deadline,
                // measured from the original press: a finger that holds perfectly
                // still sends no further events and must still start the speed-up.
                // The floor keeps the timeout branch alive even if the press already
                // outlived the timeout before this loop ran (heavy jank), which
                // otherwise would leave a long press inert.
                val undecided = axis == null && !speedBoosted
                val remaining = (viewConfiguration.longPressTimeoutMillis -
                    (SystemClock.uptimeMillis() - down.uptimeMillis)).coerceAtLeast(1L)
                val event = if (undecided) {
                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Final) }
                } else {
                    awaitPointerEvent(PointerEventPass.Final)
                }
                if (event == null) {
                    // Timeout with no decisive movement: long press.
                    speedBoosted = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    currentSpeedStart(currentSpeedWhilePressed)
                    gesture.show(PlayerGestureKind.Speed, currentSpeedWhilePressed)
                    continue
                }
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change == null || change.isConsumed) {
                    // A control took this touch, or the finger left the surface.
                    done = true
                    continue
                }
                when {
                    !change.pressed -> {
                        if (axis == null && !speedBoosted) {
                            // Lifted without becoming a drag or a long press: a tap,
                            // or the first tap of a double tap.
                            if (awaitSecondTap(pointerId) != null) {
                                currentTogglePlayPause()
                                // Swallow the rest of the second tap, otherwise its
                                // release would read as one more tap.
                                awaitAllPointersUp()
                            } else {
                                currentToggleControls()
                            }
                        }
                        done = true
                    }

                    axis != null -> applyGestureDrag(
                        axis = axis,
                        position = change.position,
                        origin = dragOrigin ?: down.position,
                        initialValue = dragInitialValue,
                        surfaceHeightPx = size.height.toFloat(),
                        brightness = brightness,
                        gesture = gesture,
                        onVolumeChange = currentVolumeChange
                    )

                    speedBoosted -> Unit // a long press runs until the finger lifts

                    else -> {
                        val delta = change.position - down.position
                        if (abs(delta.x) >= slopPx && abs(delta.x) >= abs(delta.y)) {
                            // Horizontal intent: scrubbing belongs to the seek bar,
                            // and dragging sideways must not end in a speed-up.
                            done = true
                            continue
                        }
                        if (abs(delta.y) >= slopPx && abs(delta.y) > abs(delta.x)) {
                            // Left half = brightness (when a window is reachable),
                            // right half = the player volume.
                            axis = if (change.position.x < size.width / 2f &&
                                brightness.canControl
                            ) {
                                PlayerGestureKind.Brightness
                            } else {
                                PlayerGestureKind.Volume
                            }
                            dragOrigin = change.position
                            dragInitialValue = if (axis == PlayerGestureKind.Brightness) {
                                brightness.currentValue
                            } else {
                                currentVolume
                            }
                            applyGestureDrag(
                                axis = axis,
                                position = change.position,
                                origin = dragOrigin,
                                initialValue = dragInitialValue,
                                surfaceHeightPx = size.height.toFloat(),
                                brightness = brightness,
                                gesture = gesture,
                                onVolumeChange = currentVolumeChange
                            )
                        }
                        // Anything else (a small or horizontal move) stays undecided:
                        // horizontal scrubbing belongs to the seek bar above us.
                    }
                }
            }
            if (speedBoosted) currentSpeedEnd()
            gesture.hide()
        }
    }
}

private fun applyGestureDrag(
    axis: PlayerGestureKind,
    position: Offset,
    origin: Offset,
    initialValue: Float,
    surfaceHeightPx: Float,
    brightness: PlayerBrightnessController,
    gesture: PlayerGestureState,
    onVolumeChange: (Float) -> Unit
) {
    // Map relative movement onto the value captured when the drag started.
    val fraction = (initialValue + (origin.y - position.y) / surfaceHeightPx).coerceIn(0f, 1f)
    when (axis) {
        PlayerGestureKind.Brightness -> {
            // An app cannot read the system slider, so the top of the range means
            // "use the system brightness" and only lower values dim the window.
            if (fraction >= 0.99f) brightness.restore() else brightness.setBrightness(fraction)
            gesture.show(axis, fraction)
        }
        PlayerGestureKind.Volume -> {
            onVolumeChange(fraction)
            gesture.show(axis, fraction)
        }
        PlayerGestureKind.Speed -> Unit
    }
}

/**
 * Waits for a second finger inside the double-tap window and returns its change, or
 * null when the window closed first (so the first tap really was a single tap).
 */
private suspend fun AwaitPointerEventScope.awaitSecondTap(firstPointerId: PointerId): PointerInputChange? =
    withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
        var second: PointerInputChange? = null
        while (second == null) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            // The first finger lifting again must not end the wait: only a *new*
            // pointer going down means a double tap.
            second = event.changes.firstOrNull { it.id != firstPointerId && it.pressed && !it.isConsumed }
        }
        second
    }

/** Drains events until every finger left the surface (used after a double tap). */
private suspend fun AwaitPointerEventScope.awaitAllPointersUp() {
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis * 4) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (event.changes.none { it.pressed }) return@withTimeoutOrNull
        }
    }
}

/**
 * Centered readout for the gesture in progress. The player screens draw this
 * themselves rather than [playerGestureSurface], so publishing a new value cannot
 * restart the pointer loop.
 */@Composable
fun PlayerGestureOverlay(
    indicator: PlayerGestureIndicator?,
    modifier: Modifier = Modifier
) {
    if (indicator == null) return
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = indicator.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = indicator.valueLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
            indicator.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .width(120.dp)
                        .padding(top = 2.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )
            }
        }
    }
}

/**
 * Applies brightness on top of the system slider through the window attribute - the
 * only route available without the WRITE_SETTINGS system permission. Restoring writes
 * BRIGHTNESS_OVERRIDE_NONE, which hands control back to the system value.
 */
internal class PlayerBrightnessController(context: Context) {

    private val window = context.findActivity()?.window

    /** False when no window is reachable (preview / non-activity context). */
    val canControl: Boolean get() = window != null

    private var lowered = false

    val currentValue: Float
        get() {
            val value = window?.attributes?.screenBrightness ?: return 1f
            if (value >= 0f) return value
            val system = runCatching {
                android.provider.Settings.System.getInt(
                    window?.context?.contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS
                ) / 255f
            }.getOrNull()
            return system ?: 1f
        }

    fun setBrightness(value: Float) {
        val attributes = window?.attributes ?: return
        attributes.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        window.attributes = attributes
        lowered = true
    }

    fun restore() {
        if (!lowered) return
        val attributes = window?.attributes ?: return
        attributes.screenBrightness = BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
        lowered = false
    }

    private companion object {
        const val BRIGHTNESS_OVERRIDE_NONE = -1.0f
        const val MIN_BRIGHTNESS = 0.08f
        const val MAX_BRIGHTNESS = 1.0f
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
