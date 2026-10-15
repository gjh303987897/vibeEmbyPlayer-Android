package com.vibeplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun PlayerSubtitleOverlay(text: String?, modifier: Modifier = Modifier) {
    text?.takeIf(String::isNotBlank)?.let { cue ->
        Text(
            text = cue,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
