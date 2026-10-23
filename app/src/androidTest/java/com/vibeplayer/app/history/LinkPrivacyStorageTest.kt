package com.vibeplayer.app.history

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import com.vibeplayer.app.data.repository.HistoryRetentionManager
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LinkPrivacyStorageTest {
    @Test fun sameUrlInPublicAndPrivateServicesHasSeparateHistoryAndResume() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "link-privacy-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
        }
        val db = Room.inMemoryDatabaseBuilder(base, VibePlayerDatabase::class.java).build()
        try {
            val retention = HistoryRetentionManager(SettingsDataStore(context), db)
            val repository = PlaybackHistoryRepository(db.playbackHistoryDao(), db.linkPlaybackHistoryDao(), db.dailyUsageStatDao(), retention, db)
            val public = ServerConfig("public", "Public Link", "", "", ServiceType.LINK)
            val private = public.copy(id = "private", name = "Private Link", privateMode = true)
            val url = "https://example.test/synthetic.mp4"
            repository.recordLinkPlayback(url, "Public", public, 10, 100)
            repository.recordLinkPlayback(url, "Private", private, 25, 100)
            assertEquals(listOf("Public"), repository.observeHistory(PlaybackSource.LINK, false).first().map { it.title })
            assertEquals(2, repository.observeHistory(PlaybackSource.LINK, true).first().size)
            assertEquals(10L, repository.resumePositionSeconds(PlaybackSource.LINK, public, url))
            assertEquals(25L, repository.resumePositionSeconds(PlaybackSource.LINK, private, url))
            assertTrue(repository.observeLinkHistory(false, private.id).first().isEmpty())
            assertEquals(1, repository.observeLinkHistory(true, private.id).first().size)
            db.playbackHistoryDao().reclassifyService(public.id, true)
            assertTrue(repository.observeLinkHistory(false).first().isEmpty())
            db.linkPlaybackHistoryDao().reclassifyService(public.id, true)
            assertTrue(repository.observeLinkHistory(false).first().isEmpty())
        } finally { db.close() }
    }
}
