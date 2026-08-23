package com.vibeplayer.app.util

import com.vibeplayer.app.model.MediaItem

/**
 * "S1E3"-style season / episode index text, or empty when the item is not an
 * episode. Used by the episode rows, continue-watching rail and anywhere an
 * episode needs a compact season/episode label.
 */
fun seasonEpisodeText(item: MediaItem): String {
    val season = item.parentIndexNumber.takeIf { it.isNotBlank() }
    val episode = item.indexNumber.takeIf { it.isNotBlank() } ?: return ""
    return if (season != null) "S${season}E${episode}" else "Ep$episode"
}
