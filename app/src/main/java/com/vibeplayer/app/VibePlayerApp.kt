package com.vibeplayer.app

import android.app.Application
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.util.CrashLogger
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Application entry point.
 *
 * Registers dependency injection (Hilt) and serves as the container for
 * application-scoped components (repositories, player manager, etc.).
 */
@HiltAndroidApp
class VibePlayerApp : Application() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate() {
        super.onCreate()
        // Write any uncaught crash to a file so it can be inspected / pulled
        // off the device, which makes hard-to-reproduce crashes diagnosable.
        CrashLogger.install(this)
        // Load the persisted language synchronously so MainActivity's
        // attachBaseContext can apply it before any UI is created.
        runBlocking {
            LocaleHelper.currentLocaleTag = settingsDataStore.language.first()
        }
    }
}
