package com.vibeplayer.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Keeps the display awake while the user is actually watching a video.
 *
 * A player screen is immersive and nothing gets touched during a film, so the
 * system screen timeout would otherwise blank the picture in the middle of a
 * scene. The flag follows the *player*, not the screen: pausing or stopping
 * releases it so the device may sleep again, and leaving the player releases it
 * through [DisposableEffect] - which is also why audio that keeps playing in the
 * background (no player screen composed) does not hold the display on.
 *
 * [buffering] counts as watching: the playhead still belongs to an active viewing
 * session, and going dark while the next segment loads is not what the user wants.
 */
@Composable
fun KeepScreenOnDuringPlayback(isPlaying: Boolean, buffering: Boolean) {
    val view = LocalView.current
    val keepScreenOn = isPlaying || buffering
    DisposableEffect(keepScreenOn) {
        // View.setKeepScreenOn is the platform API for this (SurfaceView uses it
        // internally for video): it holds FLAG_KEEP_SCREEN_ON on the window while
        // the view asks for it, and detaching the view clears the window as well.
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}
