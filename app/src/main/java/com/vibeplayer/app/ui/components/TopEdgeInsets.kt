package com.vibeplayer.app.ui.components

import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/**
 * Keeps content that sits against the top edge of the window - player overlays,
 * floating back buttons over artwork - clear of status bars, notches and camera
 * cutouts.
 *
 * The app draws edge to edge and the nav host deliberately hands the top inset to
 * each screen, so anything positioned with a bare `padding(...)` at the top ends
 * up under the status bar. Which devices that affects depends on where the cutout
 * is, which is why it only showed up on some phones.
 */
fun Modifier.topEdgeInsets(): Modifier =
    statusBarsPadding().displayCutoutPadding()
