package com.vibeplayer.app.model

/**
 * A media item returned by Emby / Jellyfin APIs.
 * Mirrors the fields of the Qt reference MediaItem.
 */
data class MediaItem(
    val id: String = "",
    val parentId: String = "",
    val name: String = "",
    val itemType: String = "",
    val isFolder: Boolean = false,
    val productionYear: String = "",
    val seriesId: String = "",
    val seriesName: String = "",
    val seriesImageTag: String = "",
    val seriesImageUrl: String = "",
    val childCount: Int = 0,
    val overview: String = "",
    val imageTag: String = "",
    val imageUrl: String = "",
    val logoImageUrl: String = "",
    val backdropImageUrl: String = "",
    val backdropImageUrls: List<String> = emptyList(),
    val communityRating: String = "",
    val officialRating: String = "",
    val runTime: String = "",
    val runTimeTicks: Long = 0L,
    val genres: String = "",
    val people: String = "",
    val peopleList: List<MediaPerson> = emptyList(),
    val seasonName: String = "",
    val indexNumber: String = "",
    val parentIndexNumber: String = "",
    val playbackPositionTicks: Long = 0L,
    val playedPercentage: Double = 0.0,
    val played: Boolean = false
) {
    /** Playback start position in seconds derived from ticks. */
    val playbackPositionSeconds: Double
        get() = if (playbackPositionTicks > 0) playbackPositionTicks / 10_000_000.0 else 0.0
}
