package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** Keeps mirrored Link and global history rows together throughout retention and manual deletion. */
@Dao
interface HistoryRetentionDao {
    @Query("SELECT id FROM playback_history WHERE played_date < :cutoffDate UNION SELECT id FROM link_playback_history WHERE played_date < :cutoffDate LIMIT 500")
    suspend fun expiredHistoryIds(cutoffDate: String): List<String>

    @Query("DELETE FROM playback_history WHERE id IN (:ids)")
    suspend fun deleteGlobalIds(ids: List<String>)

    @Query("DELETE FROM link_playback_history WHERE id IN (:ids)")
    suspend fun deleteLinkIds(ids: List<String>)

    @Query("DELETE FROM daily_usage_stats WHERE stat_date < :cutoffDate")
    suspend fun pruneUsageBefore(cutoffDate: String)

    @Transaction
    suspend fun pruneBefore(cutoffDate: String) {
        // Bound SQL bind parameters and heap use even on an old installation with years of history.
        // Collect both mirrored dates before deleting either side, including inconsistent old pairs.
        while (true) {
            val ids = expiredHistoryIds(cutoffDate)
            if (ids.isEmpty()) break
            deleteLinkIds(ids)
            deleteGlobalIds(ids)
        }
        pruneUsageBefore(cutoffDate)
    }

    @Transaction
    suspend fun deleteHistory(id: String) {
        deleteLinkIds(listOf(id))
        deleteGlobalIds(listOf(id))
    }
}
