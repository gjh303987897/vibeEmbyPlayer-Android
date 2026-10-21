package com.vibeplayer.app.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.ui.player.PlayerControls
import com.vibeplayer.app.ui.player.PlayerSettings
import com.vibeplayer.app.ui.player.PlayerSettingsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackQueueSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun queueSelectionBoundariesAutoplayPersistenceAndCenteredControlsInBothOrientations() {
        val app = compose.activity.application as VibePlayerApp
        val manager = app.playerManager
        val originalAuto = runBlocking { app.settingsDataStore.playerPreferences.first().autoPlayNext }
        val originalOrientation = compose.activity.requestedOrientation
        val entries = (1..120).map { PlaybackQueueItem("$it", "Queue $it") }
        try {
            compose.runOnUiThread {
                manager.beginLoading()
                manager.queue.stage(PlaybackQueueScope(PlaybackSource.LOCAL), entries, "1")
                manager.queue.attach(this, PlaybackQueueScope(PlaybackSource.LOCAL), entries.first(), {}, {})
                val settings = ViewModelProvider(compose.activity)[PlayerSettingsViewModel::class.java]
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                compose.activity.setContent {
                    val preferences by settings.preferences.collectAsState()
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            PlayerControls(settings)
                            PlayerSettings(true, false, preferences, settings)
                        }
                    }
                }
            }
            for ((requested, orientation) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
            )) {
                compose.runOnUiThread {
                    compose.activity.requestedOrientation = requested
                    manager.queue.select(0)
                }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == orientation }
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_previous_item)).assertIsNotEnabled()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_next_item)).assertIsEnabled().performClick()
                compose.waitUntil { manager.queue.state.value.index == 1 }
                val playBounds = compose.onNodeWithContentDescription(compose.activity.getString(R.string.play))
                    .fetchSemanticsNode().boundsInRoot
                val screenBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertEquals(screenBounds.center.x, playBounds.center.x, 2f)
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_settings)).performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.player_queue)).performClick()
                compose.onNodeWithText("Queue 2").assertIsDisplayed().assertIsSelected()
                val previous = manager.preferences.value.autoPlayNext
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_auto_play_next)).performClick()
                compose.waitUntil { manager.preferences.value.autoPlayNext != previous }
                assertEquals(!previous, runBlocking { app.settingsDataStore.playerPreferences.first().autoPlayNext })
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(119)
                compose.onNodeWithText("Queue 120").performClick()
                compose.waitUntil { manager.queue.state.value.index == 119 }
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_next_item)).assertIsNotEnabled()
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_previous_item)).assertIsEnabled().performClick()
                compose.waitUntil { manager.queue.state.value.index == 118 }
            }
        } finally {
            compose.runOnUiThread {
                manager.queue.detach(this)
                manager.stop()
                compose.activity.requestedOrientation = originalOrientation
            }
            runBlocking { app.settingsDataStore.setAutoPlayNext(originalAuto) }
        }
    }
}
