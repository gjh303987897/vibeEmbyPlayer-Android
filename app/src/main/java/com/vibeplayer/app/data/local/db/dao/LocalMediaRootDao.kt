package com.vibeplayer.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMediaRootDao {

    @Query("SELECT * FROM local_media_roots ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<LocalMediaRootEntity>>

    @Query("SELECT * FROM local_media_roots ORDER BY sort_order ASC")
    suspend fun getAll(): List<LocalMediaRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(root: LocalMediaRootEntity)

    @Query("DELETE FROM local_media_roots WHERE id = :id")
    suspend fun deleteById(id: String)
}
