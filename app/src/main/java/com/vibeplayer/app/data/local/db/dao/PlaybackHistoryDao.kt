package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Query("SELECT * FROM playback_history ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observePaged(limit: Int, offset: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT * FROM playback_history WHERE source = :source ORDER BY played_at_ms DESC LIMIT :limit OFFSET :offset")
    fun observeBySource(source: String, limit: Int, offset: Int): Flow<List<PlaybackHistoryEntity>>

    @Query("SELECT COUNT(*) FROM playback_history")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM playback_history WHERE source = :source")
    fun observeCountBySource(source: String): Flow<Int>

    @Query("SELECT * FROM playback_history WHERE source = :source AND service_id = :serviceId AND replay_target = :replayTarget LIMIT 1")
    suspend fun findByStableIdentity(source: String, serviceId: String, replayTarget: String): PlaybackHistoryEntity?

    @Query("SELECT * FROM playback_history WHERE id = :id")
    suspend fun findById(id: String): PlaybackHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: PlaybackHistoryEntity)

    @Query(
        "UPDATE playback_history SET position_seconds = :positionSeconds, duration_seconds = :durationSeconds, " +
            "completed = :completed, updated_at_ms = :updatedAtMs WHERE id = :id"
    )
    suspend fun updateProgress(id: String, positionSeconds: Long, durationSeconds: Long, completed: Boolean, updatedAtMs: Long)

    @Query("DELETE FROM playback_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM playback_history WHERE service_id = :serviceId")
    suspend fun deleteByServiceId(serviceId: String)

    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun count(): Int
}
