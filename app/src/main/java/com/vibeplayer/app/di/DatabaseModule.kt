package com.vibeplayer.app.di

import android.content.Context
import androidx.room.Room
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import com.vibeplayer.app.data.local.db.Migrations
import com.vibeplayer.app.data.local.db.dao.DailyUsageStatDao
import com.vibeplayer.app.data.local.db.dao.IptvChannelDao
import com.vibeplayer.app.data.local.db.dao.IptvPlaylistDao
import com.vibeplayer.app.data.local.db.dao.LinkPlaybackHistoryDao
import com.vibeplayer.app.data.local.db.dao.LocalMediaRootDao
import com.vibeplayer.app.data.local.db.dao.PlaybackHistoryDao
import com.vibeplayer.app.data.local.db.dao.TransferTaskDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room [VibePlayerDatabase] and its DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VibePlayerDatabase =
        Room.databaseBuilder(context, VibePlayerDatabase::class.java, "vibeplayer.db")
            .addMigrations(Migrations.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePlaybackHistoryDao(db: VibePlayerDatabase): PlaybackHistoryDao = db.playbackHistoryDao()

    @Provides
    fun provideLinkPlaybackHistoryDao(db: VibePlayerDatabase): LinkPlaybackHistoryDao = db.linkPlaybackHistoryDao()

    @Provides
    fun provideDailyUsageStatDao(db: VibePlayerDatabase): DailyUsageStatDao = db.dailyUsageStatDao()

    @Provides
    fun provideIptvPlaylistDao(db: VibePlayerDatabase): IptvPlaylistDao = db.iptvPlaylistDao()

    @Provides
    fun provideIptvChannelDao(db: VibePlayerDatabase): IptvChannelDao = db.iptvChannelDao()

    @Provides
    fun provideLocalMediaRootDao(db: VibePlayerDatabase): LocalMediaRootDao = db.localMediaRootDao()

    @Provides
    fun provideTransferTaskDao(db: VibePlayerDatabase): TransferTaskDao = db.transferTaskDao()
}
