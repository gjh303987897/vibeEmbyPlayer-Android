package com.vibeplayer.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Anchored above the bottom-right actions, without dimming the entire video. */
@Composable
internal fun PlayerSettingsPopup(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val maxHeight = (configuration.screenHeightDp.dp - safe.calculateTopPadding() -
        safe.calculateBottomPadding() - 80.dp).coerceAtLeast(80.dp)
    val maxWidth = (configuration.screenWidthDp.dp - safe.calculateLeftPadding(direction) -
        safe.calculateRightPadding(direction) - 16.dp).coerceAtLeast(80.dp)
    val position = remember(density) { SettingsPopupPosition(with(density) { 8.dp.roundToPx() }) }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, onPrimary = Color.Black,
            surface = Color.Transparent, onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .7f))) {
            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE61C1C1C),
                modifier = Modifier.widthIn(max = maxWidth)
                    .width(360.dp).heightIn(max = maxHeight)) {
                Column(content = content)
            }
        }
    }
}

private class SettingsPopupPosition(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val right = (windowSize.width - popupContentSize.width - gap).coerceAtLeast(gap)
        val bottom = (windowSize.height - popupContentSize.height - gap).coerceAtLeast(gap)
        return IntOffset((anchorBounds.right - popupContentSize.width).coerceIn(gap, right),
            (anchorBounds.top - popupContentSize.height - gap).coerceIn(gap, bottom))
    }
}

@Composable
internal fun PlayerSettingMenuRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 56.dp)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        if (!value.isNullOrEmpty()) Text(value, Modifier.widthIn(max = 116.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
