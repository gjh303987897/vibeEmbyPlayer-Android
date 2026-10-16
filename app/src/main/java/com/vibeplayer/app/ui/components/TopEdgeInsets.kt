package com.vibeplayer.app.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

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

/** Keeps controls centred on the screen even when a landscape cutout occupies only one side. */
@Composable
fun Modifier.bottomEdgeInsets(): Modifier {
    val safe = WindowInsets.safeDrawing
    return windowInsetsPadding(remember(safe) { CentredBottomInsets(safe) })
}

/** Read at layout time so animated system bars are still handled by windowInsetsPadding. */
private class CentredBottomInsets(private val safe: WindowInsets) : WindowInsets {
    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int =
        maxOf(safe.getLeft(density, layoutDirection), safe.getRight(density, layoutDirection))

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int =
        getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int = 0

    override fun getBottom(density: Density): Int = safe.getBottom(density)
}
