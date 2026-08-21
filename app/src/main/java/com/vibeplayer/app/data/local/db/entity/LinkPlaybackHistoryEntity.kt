package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Replayable history for the built-in Link media source, ported from the Qt
 * LinkPlaybackHistoryItem. Grouped by date in the UI.
 */
@Entity(
    tableName = "link_playback_history",
    indices = [Index("played_at_ms")]
)
data class LinkPlaybackHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "playback_url") val playbackUrl: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "display_address") val displayAddress: String,
    @ColumnInfo(name = "played_date") val playedDate: String,
    @ColumnInfo(name = "played_at_ms") val playedAtMs: Long,
    @ColumnInfo(name = "privacy_mode") val privacyMode: Boolean
)
