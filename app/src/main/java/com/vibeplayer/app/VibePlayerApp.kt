package com.vibeplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.util.CrashLogger
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.HiltAndroidApp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vibeplayer.app.security.PrivacyManager
import com.vibeplayer.app.player.PlayerManager
import com.vibeplayer.app.data.repository.ActiveSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Registers dependency injection (Hilt) and serves as the container for
 * application-scoped components (repositories, player manager, etc.).
 */
@HiltAndroidApp
class VibePlayerApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject lateinit var privacyManager: PrivacyManager
    @Inject lateinit var playerManager: PlayerManager
    @Inject lateinit var activeSessionManager: ActiveSessionManager

    /**
     * App-wide image loader with a gentle crossfade so artwork fades in
     * naturally instead of popping.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .crossfade(true)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Write any uncaught crash to a file so it can be inspected / pulled
        // off the device, which makes hard-to-reproduce crashes diagnosable.
        CrashLogger.install(this)
        // DataStore is asynchronous and cannot be awaited from Application.onCreate.
        // The settings store keeps a tiny non-sensitive bootstrap mirror for the
        // Activity locale hook; the canonical value remains in DataStore.
        LocaleHelper.currentLocaleTag = settingsDataStore.initialLanguage
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = privacyManager.scheduleBackgroundRelock()
            override fun onStart(owner: LifecycleOwner) = privacyManager.cancelBackgroundRelock()
        })
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var wasUnlocked = false
            privacyManager.privacyMode.collect { unlocked ->
                if (wasUnlocked && !unlocked) {
                    val activeServerIsPrivate = activeSessionManager.activeSession.value?.server?.privateMode == true
                    if (playerManager.state.value.privatePlayback) {
                        playerManager.player.pause()
                        playerManager.player.clearMediaItems()
                        playerManager.beginLoading()
                        privacyManager.notifyPrivatePlaybackStopped()
                    }
                    if (activeServerIsPrivate) activeSessionManager.setActiveSession(null)
                }
                wasUnlocked = unlocked
            }
        }
    }
}
