package com.vibeplayer.app.security

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.remote.EmbyClient
import com.vibeplayer.app.data.remote.JellyfinClient
import com.vibeplayer.app.data.remote.MediaNetworkClient
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.player.PipEntryBlock
import com.vibeplayer.app.player.WebDavTsslSeekFixture
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.ui.link.LinkPlayerViewModel
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.settings.SettingsScreen
import com.vibeplayer.app.ui.settings.SettingsViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses production UI/ViewModels and native playback, with isolated synthetic account data. */
@RunWith(AndroidJUnit4::class)
class PrivacyUiPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun pinDialogsMaskInputAndChangingPinRequiresCurrentPin() {
        SecurityDeviceHarness().use { h ->
            val models = ViewModelStore()
            lateinit var viewModel: SettingsViewModel
            val text = { id: Int -> compose.activity.getString(id) }
            try {
                compose.runOnUiThread {
                    viewModel = SettingsViewModel(h.settings, h.privacy, h.retention)
                    models.put("settings", viewModel)
                    compose.activity.setContent { MaterialTheme { SettingsScreen(viewModel) } }
                }
                compose.onNodeWithText(text(R.string.settings_set_pin)).performScrollTo().performClick()
                compose.onNode(isPassword()).assertCountEqualsSingle().performTextInput("1234")
                captureDialog("first-pin-masked.png")
                compose.onNodeWithText(text(R.string.settings_confirm)).performClick()
                compose.waitUntil(15_000) { viewModel.uiState.value.pinConfigured }
                assertFalse(h.privacy.privacyMode.value)
                compose.onNodeWithText(text(R.string.settings_change_pin)).performScrollTo().performClick()
                compose.onAllNodes(isPassword()).assertCountEquals(2)
                compose.onNodeWithText(text(R.string.settings_current_pin)).performTextInput("0000")
                compose.onNodeWithText(text(R.string.settings_pin_placeholder)).performTextInput("5678")
                captureDialog("change-pin-masked.png")
                compose.onNodeWithText(text(R.string.settings_confirm)).performClick()
                compose.waitUntil(15_000) {
                    compose.onAllNodesWithText(text(R.string.settings_wrong_pin)).fetchSemanticsNodes().isNotEmpty()
                }
                assertTrue(h.privacy.verifyPin("1234"))
                assertFalse(h.privacy.verifyPin("5678"))
                compose.onNodeWithText(text(R.string.settings_current_pin)).performTextReplacement("1234")
                compose.onNodeWithText(text(R.string.settings_confirm)).performClick()
                compose.waitUntil(15_000) { compose.onAllNodes(isPassword()).fetchSemanticsNodes().isEmpty() }
                assertTrue(h.privacy.verifyPin("5678"))
                assertFalse(h.privacy.verifyPin("1234"))
                compose.onNodeWithText(text(R.string.settings_enter_privacy)).performScrollTo().performClick()
                compose.onNode(isPassword()).performTextInput("5678")
                compose.onNodeWithText(text(R.string.settings_confirm)).performClick()
                compose.waitUntil(15_000) { viewModel.uiState.value.privacyActive }
                compose.onNodeWithText(text(R.string.settings_lock_privacy)).performScrollTo().performClick()
                compose.waitUntil(5_000) { !h.privacy.privacyMode.value }
            } finally { compose.runOnUiThread { models.clear() } }
        }
    }

    @Test fun privateLinkGatesPlaybackRedactsSystemMetadataAndStopsOnRelock() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VibePlayerApp
        val manager = app.playerManager
        SecurityDeviceHarness().use { h ->
            WebDavTsslSeekFixture(app, instrumentation.context, "segment_014.ts").use { fixture ->
                val playback = fixture.prepare()
                val server = ServerConfig("private-link", "Synthetic private service", "", "", ServiceType.LINK, privateMode = true)
                runBlocking { h.services.upsert(server) }
                assertTrue(h.privacy.setPin("1234"))
                val json = Json { ignoreUnknownKeys = true }
                val network = MediaNetworkClient(OkHttpClientFactory())
                val repository = MediaServerRepository(EmbyClient(network, json), JellyfinClient(network, json),
                    h.services, h.database.playbackHistoryDao(), h.database.dailyUsageStatDao(),
                    SecureSessionStore(h.context, KeystoreSecretStore(h.context)), ActiveSessionManager(),
                    h.database.linkPlaybackHistoryDao())
                val models = ViewModelStore()
                lateinit var viewModel: LinkPlayerViewModel
                val created = CountDownLatch(1)
                val owner = Any()
                val encoded = Routes.linkPlayer(playback.playUrl, server.id).substringAfterLast('/')
                try {
                    compose.runOnUiThread {
                        manager.stop()
                        viewModel = LinkPlayerViewModel(manager, h.history, repository, h.privacy, h.scope)
                        models.put("link", viewModel)
                        compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        compose.activity.pictureInPicture.attachPlayer(owner)
                        compose.activity.setContent {
                            val state by manager.state.collectAsState()
                            Box(Modifier.fillMaxSize()) {
                                AndroidView(factory = { context -> IjkPlayerView(context, { holder, attached ->
                                    manager.setVideoSurface(holder, attached)
                                    if (attached) created.countDown()
                                }).apply { player = manager.player } }, update = {
                                    it.updateSurfaceGeneration(state.surfaceGeneration)
                                }, modifier = Modifier.fillMaxSize())
                            }
                        }
                        viewModel.play(encoded, server.id)
                    }
                    compose.waitUntil(10_000) { created.count == 0L }
                    compose.waitUntil(10_000) { manager.state.value.error == "Service is locked" }
                    assertFalse(manager.state.value.isPrepared)
                    assertTrue(runBlocking { h.history.observeHistory(PlaybackSource.LINK, true).first() }.isEmpty())
                    assertTrue(h.privacy.openPrivacy("1234"))
                    compose.runOnUiThread { viewModel.play(encoded, server.id) }
                    compose.waitUntil(25_000) { manager.state.value.isPrepared && manager.state.value.isPlaying &&
                        manager.state.value.positionMs >= 500 }
                    assertTrue(manager.state.value.privatePlayback)
                    compose.waitUntil(5_000) { compose.activity.pictureInPicture.state.value.entryBlock == PipEntryBlock.PRIVATE_VIDEO }
                    assertFalse(compose.activity.pictureInPicture.state.value.canEnter)
                    compose.runOnUiThread { ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java)) }
                    compose.waitUntil(5_000) {
                        app.getSystemService(NotificationManager::class.java).activeNotifications.any {
                            it.id == PlaybackService.NOTIFICATION_ID && it.notification.extras.getString(Notification.EXTRA_TITLE) ==
                                app.getString(R.string.playback_notification_title)
                        }
                    }
                    instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
                    try {
                        val sessions = app.getSystemService(MediaSessionManager::class.java)
                        compose.waitUntil(5_000) {
                            sessions.getActiveSessions(null).filter { it.packageName == app.packageName }.any {
                                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) == "VibePlayer"
                            }
                        }
                        val metadata = sessions.getActiveSessions(null).first { it.packageName == app.packageName }.metadata!!
                        assertEquals("", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                        assertEquals(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
                    } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
                    compose.runOnUiThread { h.privacy.exitPrivacyMode() }
                    compose.waitUntil(10_000) { !manager.state.value.isPrepared && manager.state.value.error == "Service is locked" }
                    assertFalse(manager.state.value.isPlaying)
                    assertTrue(runBlocking { h.history.observeHistory(PlaybackSource.LINK, false).first() }.isEmpty())
                    val history = runBlocking { h.history.observeHistory(PlaybackSource.LINK, true).first() }
                    assertEquals(1, history.size)
                    assertEquals(server.id, history.single().serviceId)
                } finally {
                    compose.runOnUiThread {
                        models.clear()
                        manager.stop()
                        compose.activity.pictureInPicture.detachPlayer(owner)
                        app.stopService(Intent(app, PlaybackService::class.java))
                    }
                    runBlocking { h.scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
                }
            }
        }
    }

    private fun captureDialog(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        try {
            val directory = File(compose.activity.getExternalFilesDir(null), "security-device-results").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun isPassword() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)

    private fun SemanticsNodeInteraction.assertCountEqualsSingle(): SemanticsNodeInteraction = apply {
        compose.onAllNodes(isPassword()).assertCountEquals(1)
        assertIsDisplayed()
    }
}
