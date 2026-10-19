package com.vibeplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibeplayer.app.player.SubtitleAppearance

@Composable
fun PlayerSubtitleOverlay(text: String?, modifier: Modifier = Modifier, appearance: SubtitleAppearance = SubtitleAppearance()) {
    text?.takeIf(String::isNotBlank)?.let { cue ->
        val safe = appearance.normalized()
        BoxWithConstraints(modifier, contentAlignment = Alignment.BottomCenter) {
            // Keep room for dialogue even on a short landscape screen with controls visible.
            val offset = safe.bottomOffsetDp.dp.coerceAtMost(maxHeight * .3f)
            Text(
                text = cue,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontSize = safe.fontSizeSp.sp,
                lineHeight = (safe.fontSizeSp * 1.4f).sp,
                modifier = Modifier.padding(bottom = offset)
                    .background(Color.Black.copy(alpha = safe.backgroundOpacityPercent / 100f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
