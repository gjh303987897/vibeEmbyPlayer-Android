package com.vibeplayer.app.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Unified playback history row. One row per stable media identity
 * (source + service + replay target), ported from the Qt PlaybackHistoryItem.
 */
@Entity(
    tableName = "playback_history",
    indices = [
        Index("played_at_ms"),
        Index("source"),
        Index(value = ["source", "service_id", "replay_target"], unique = true)
    ]
)
data class PlaybackHistoryEntity(
    @PrimaryKey val id: String,
    val source: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "service_name") val serviceName: String,
    @ColumnInfo(name = "replay_target") val replayTarget: String,
    val title: String,
    val subtitle: String,
    @ColumnInfo(name = "display_target") val displayTarget: String,
    @ColumnInfo(name = "played_date") val playedDate: String,
    @ColumnInfo(name = "played_at_ms") val playedAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "position_seconds") val positionSeconds: Long,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long,
    val completed: Boolean,
    @ColumnInfo(name = "privacy_mode") val privacyMode: Boolean,
    val available: Boolean
)
