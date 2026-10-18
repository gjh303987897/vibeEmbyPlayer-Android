package com.vibeplayer.app.ui.services

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val LocalServiceCardScale = staticCompositionLocalOf { 1f }

/** Scale layout measurements, not the rendered layer, so touch targets and text stay accessible. */
@Composable
internal fun ServiceCardSize(percent: Int, content: @Composable () -> Unit) {
    val factor = percent.coerceIn(50, 150) / 100f
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val width = (maxWidth * factor).coerceAtLeast(minOf(320.dp, maxWidth)).coerceAtMost(maxWidth)
        CompositionLocalProvider(LocalServiceCardScale provides factor) {
            Box(Modifier.widthIn(max = width), contentAlignment = Alignment.TopCenter) { content() }
        }
    }
}
