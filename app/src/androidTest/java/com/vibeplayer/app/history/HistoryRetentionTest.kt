package com.vibeplayer.app.history

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import com.vibeplayer.app.data.local.db.entity.*
import com.vibeplayer.app.data.repository.HistoryRetentionManager
import com.vibeplayer.app.domain.history.HistoryRetentionPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryRetentionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun shorteningRetentionPrunesMirrorsAndStatsAndQueriesAgree() = runBlocking {
        val folder = File(context.cacheDir, "retention-${UUID.randomUUID()}").apply { mkdirs() }
        val scopedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
        }
        val settings = SettingsDataStore(scopedContext)
        val database = Room.inMemoryDatabaseBuilder(context, VibePlayerDatabase::class.java).build()
        try {
            val today = HistoryRetentionPolicy.today()
            val cutoff = HistoryRetentionPolicy.cutoffDate(today, 7)
            val old = today.minusDays(7).toString()
            // More than one DAO batch, with both private/public rows and mirrored Link rows.
            repeat(505) { index ->
                val id = "old-$index"
                database.playbackHistoryDao().upsert(history(id, old, index % 2 == 0))
                database.linkPlaybackHistoryDao().upsert(link(id, old, index % 2 == 0))
            }
            database.playbackHistoryDao().upsert(history("boundary", cutoff, false))
            database.playbackHistoryDao().upsert(history("private", today.toString(), true))
            database.playbackHistoryDao().upsert(history("mismatch", today.toString(), false))
            database.linkPlaybackHistoryDao().upsert(link("mismatch", old, false))
            database.dailyUsageStatDao().upsert(usage(old))
            database.dailyUsageStatDao().upsert(usage(cutoff))

            val manager = HistoryRetentionManager(settings, database)
            assertEquals(30, settings.historyRetentionDays.first())
            manager.setRetentionDays(7)
            assertEquals(7, settings.historyRetentionDays.first())
            assertEquals(cutoff, manager.cutoffDate.first())
            val dao = database.playbackHistoryDao()
            assertEquals(2, dao.count())
            assertEquals(listOf("boundary"), dao.observePaged(false, 10, 0, cutoff).first().map { it.id })
            assertEquals(dao.observePaged(true, 10, 0, cutoff).first().size, dao.observeCount(true, cutoff).first())
            assertEquals(0, database.linkPlaybackHistoryDao().observePaged(true, 100, 0).first().size)
            assertEquals(listOf(cutoff), database.dailyUsageStatDao().observeAll(true).first().map { it.date })
            assertNull(dao.findById("mismatch"))
            manager.setRetentionDays(365)
            assertEquals("increasing retention cannot resurrect pruned rows", 2, dao.count())
        } finally { database.close() }
    }

    @Test fun queryFiltersBeforePagingEvenBeforePhysicalCleanup() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, VibePlayerDatabase::class.java).build()
        try {
            database.playbackHistoryDao().upsert(history("expired", "2026-09-01", false).copy(playedAtMs = 100))
            database.playbackHistoryDao().upsert(history("keep", "2026-10-01", false))
            val dao = database.playbackHistoryDao()
            assertEquals(listOf("keep"), dao.observePaged(false, 1, 0, "2026-10-01").first().map { it.id })
            assertEquals(1, dao.observeCount(false, "2026-10-01").first())
        } finally { database.close() }
    }

    private fun history(id: String, date: String, isPrivate: Boolean) = PlaybackHistoryEntity(
        id, "Link", "", "Link", id, id, "", id, date, 1, 1, 0, 0, false, isPrivate, true)
    private fun link(id: String, date: String, isPrivate: Boolean) = LinkPlaybackHistoryEntity(id, id, id, id, date, 1, isPrivate)
    private fun usage(date: String) = DailyUsageStatEntity(date, "", "Local", "", 30, 1000, 0, 0, 0, false)
}
