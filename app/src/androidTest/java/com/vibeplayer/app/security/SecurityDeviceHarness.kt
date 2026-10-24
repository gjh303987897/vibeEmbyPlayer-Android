package com.vibeplayer.app.security

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.local.datastore.ServiceStore
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import com.vibeplayer.app.data.repository.HistoryRetentionManager
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json

/** Isolates every test service, PIN, setting and history from the installed app's data. */
internal class SecurityDeviceHarness : Closeable {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "security-device-${UUID.randomUUID()}-"
    private val root = File(app.cacheDir, prefix).apply { check(mkdirs()) }
    private val preferenceFiles = mutableSetOf<String>()
    val context = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(prefix + name, mode).also { preferenceFiles += prefix + name }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val privacy = PrivacyManager(context, scope)
    val services = ServiceStore(context, Json { ignoreUnknownKeys = true })
    val settings = SettingsDataStore(context)
    val database = Room.inMemoryDatabaseBuilder(app, VibePlayerDatabase::class.java).build()
    val retention = HistoryRetentionManager(settings, database)
    val history = PlaybackHistoryRepository(database.playbackHistoryDao(), database.linkPlaybackHistoryDao(),
        database.dailyUsageStatDao(), retention, database)

    override fun close() {
        scope.cancel()
        database.close()
        preferenceFiles.forEach { app.deleteSharedPreferences(it) }
        check(root.deleteRecursively())
    }
}
