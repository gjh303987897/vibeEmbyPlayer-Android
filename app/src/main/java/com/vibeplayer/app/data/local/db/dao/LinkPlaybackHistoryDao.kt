package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinkPlaybackHistoryDao {

    @Query("SELECT * FROM link_playback_history WHERE (:showPrivate OR privacy_mode = 0) ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observePaged(showPrivate: Boolean, limit: Int, offset: Int): Flow<List<LinkPlaybackHistoryEntity>>

    @Query("SELECT * FROM link_playback_history WHERE playback_url = :url LIMIT 1")
    suspend fun findByUrl(url: String): LinkPlaybackHistoryEntity?

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
