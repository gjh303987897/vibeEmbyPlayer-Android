package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkPlaybackHistoryDao {

    @Query("SELECT l.* FROM link_playback_history l JOIN playback_history p ON p.id = l.id WHERE l.played_date >= :cutoffDate AND (:showPrivate OR (l.privacy_mode = 0 AND p.privacy_mode = 0)) AND (:serviceId IS NULL OR p.service_id = :serviceId) ORDER BY l.played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observePaged(showPrivate: Boolean, limit: Int, offset: Int, cutoffDate: String = "", serviceId: String? = null): Flow<List<LinkPlaybackHistoryEntity>>

    @Query("SELECT l.* FROM link_playback_history l JOIN playback_history p ON p.id = l.id WHERE l.playback_url = :url AND p.service_id = :serviceId LIMIT 1")
    suspend fun findByUrl(url: String, serviceId: String = ""): LinkPlaybackHistoryEntity?

    @Query("UPDATE link_playback_history SET privacy_mode = :privateMode WHERE id IN (SELECT id FROM playback_history WHERE service_id = :serviceId)")
    suspend fun reclassifyService(serviceId: String, privateMode: Boolean)

    @Query("SELECT * FROM link_playback_history WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LinkPlaybackHistoryEntity?

    @Query("UPDATE link_playback_history SET display_name = :displayName, display_address = :displayAddress, played_date = :playedDate, played_at_ms = :playedAtMs, privacy_mode = :privateMode WHERE id = :id")
    suspend fun markPlaybackOccurrence(
        id: String,
        displayName: String,
        displayAddress: String,
        playedDate: String,
        playedAtMs: Long,
        privateMode: Boolean
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LinkPlaybackHistoryEntity)

    @Query("DELETE FROM link_playback_history WHERE id = :id")
    suspend fun deleteById(id: String)
}
