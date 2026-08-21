package com.vibeplayer.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vibeplayer.app.data.local.db.dao.DailyUsageStatDao
import com.vibeplayer.app.data.local.db.dao.IptvChannelDao
import com.vibeplayer.app.data.local.db.dao.IptvPlaylistDao
import com.vibeplayer.app.data.local.db.dao.LinkPlaybackHistoryDao
import com.vibeplayer.app.data.local.db.dao.LocalMediaRootDao
import com.vibeplayer.app.data.local.db.dao.PlaybackHistoryDao
import com.vibeplayer.app.data.local.db.dao.TransferTaskDao
import com.vibeplayer.app.data.local.db.entity.DailyUsageStatEntity
import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.local.db.entity.IptvPlaylistEntity
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.data.local.db.entity.PlaybackHistoryEntity
import com.vibeplayer.app.data.local.db.entity.TransferTaskEntity

/**
 * Room database mirroring the Qt reference SessionRepository schema. Version 1
 * holds the core persisted state: unified playback history, link
 * history, daily usage stats, IPTV playlists/channels, transfer queue and
 * local media roots.
 */
@Database(
    entities = [
        PlaybackHistoryEntity::class,
        LinkPlaybackHistoryEntity::class,
        DailyUsageStatEntity::class,
        IptvPlaylistEntity::class,
        IptvChannelEntity::class,
        LocalMediaRootEntity::class,
        TransferTaskEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class VibePlayerDatabase : RoomDatabase() {
    abstract fun playbackHistoryDao(): PlaybackHistoryDao
    abstract fun linkPlaybackHistoryDao(): LinkPlaybackHistoryDao
    abstract fun dailyUsageStatDao(): DailyUsageStatDao
    abstract fun iptvPlaylistDao(): IptvPlaylistDao
    abstract fun iptvChannelDao(): IptvChannelDao
    abstract fun localMediaRootDao(): LocalMediaRootDao
    abstract fun transferTaskDao(): TransferTaskDao
}
