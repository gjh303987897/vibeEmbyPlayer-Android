package com.vibeplayer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Poster image with a rounded-corner tonal placeholder fallback, a soft shadow
 * and a gentle crossfade when the artwork loads. Used by the media grids and
 * rails.
 */
@Composable
fun MediaPoster(
    url: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Outlined.Movie,
    cornerRadius: Int = 12,
    elevation: Float = 3f
) {
    val shape = RoundedCornerShape(cornerRadius.dp)
    Box(
        modifier = modifier
            .shadow(elevation = elevation.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.22f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val imageUrl = url?.takeIf { it.isNotBlank() }
        if (imageUrl == null) {
            Placeholder(placeholderIcon, MaterialTheme.colorScheme.surfaceVariant)
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun Placeholder(icon: ImageVector, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
