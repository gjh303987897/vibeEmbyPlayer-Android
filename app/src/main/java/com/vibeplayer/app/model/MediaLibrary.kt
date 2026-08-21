package com.vibeplayer.app.model

/** A media library / user view on an Emby or Jellyfin server. */
data class MediaLibrary(
    val id: String,
    val name: String,
    val collectionType: String,
    val itemType: String,
    val imageTag: String,
    val imageUrl: String,
    val childCount: Int
)
