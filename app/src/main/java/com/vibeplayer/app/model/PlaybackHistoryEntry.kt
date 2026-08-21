package com.vibeplayer.app.model

/**
 * A single unified playback history occurrence, ported from the Qt
 * PlaybackHistoryItem. One entry per stable media identity
 * (source + service + replay target).
 */
data class PlaybackHistoryEntry(
    val id: String,
    val source: PlaybackSource,
    val serviceId: String,
    val serviceName: String,
    val replayTarget: String,
    val title: String,
    val subtitle: String = "",
    val displayTarget: String = "",
    val playedDate: String = "",
    val playedAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val completed: Boolean = false,
    val privacyMode: Boolean = false,
    val available: Boolean = true
) {
    /** True when there is a known duration to draw a progress track against. */
    val hasDuration: Boolean get() = durationSeconds > 0

    val progress: Float
        get() = if (hasDuration) (positionSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f) else 0f
}
