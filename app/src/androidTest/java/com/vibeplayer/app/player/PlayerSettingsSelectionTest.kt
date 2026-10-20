package com.vibeplayer.app.player

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.domain.subtitle.TextSubtitles
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.ui.components.PlayerSubtitleOverlay
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.player.PlayerControls
import com.vibeplayer.app.ui.player.PlayerSettings
import com.vibeplayer.app.ui.player.PlayerSettingsViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/** Clicks the production settings and checks their effects on the real singleton/native player. */
@RunWith(AndroidJUnit4::class)
class PlayerSettingsSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsSelectNativeSpeedAndEmbeddedOrPhoneSubtitlesInBothOrientations() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as VibePlayerApp
        val manager = app.playerManager
        val originalTextLanguage = runBlocking { app.settingsDataStore.preferredTextLanguage.first() }
        val originalHoldSpeed = runBlocking { app.settingsDataStore.playerPreferences.first().longPressSpeed }
        val originalScaleMode = runBlocking { app.settingsDataStore.playerPreferences.first().videoScaleMode }
        val originalOrientation = compose.activity.requestedOrientation
        val sample = File.createTempFile("settings-selection-", ".mp4", app.cacheDir)
        instrumentation.context.resources.openRawResource(com.vibeplayer.app.test.R.raw.video_sample_av1)
            .use { input -> sample.outputStream().use { input.copyTo(it) } }
        val text = { id: Int -> compose.activity.getString(id) }
        try {
            compose.runOnUiThread {
                manager.stop()
                manager.setPlaybackSpeed(1f)
                val viewModel = ViewModelProvider(compose.activity)[PlayerSettingsViewModel::class.java]
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                compose.activity.setContent {
                    val state by manager.state.collectAsState()
                    val preferences by manager.preferences.collectAsState()
                    val zoom by manager.videoZoom.collectAsState()
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            AndroidView(factory = { context ->
                                IjkPlayerView(context, manager::setVideoSurface).apply { player = manager.player }
                            }, update = {
                                it.updateSurfaceGeneration(state.surfaceGeneration)
                                it.updateVideoSize()
                                it.updatePresentation(preferences.videoScaleMode, zoom)
                            }, modifier = Modifier.fillMaxSize())
                            PlayerSubtitleOverlay(state.subtitleText,
                                Modifier.align(Alignment.Center).testTag("visible-settings-subtitle"), preferences.subtitleAppearance)
                            PlayerControls(viewModel, Modifier.align(Alignment.BottomCenter))
                            PlayerSettings(true, false, preferences, viewModel)
                        }
                    }
                }
                manager.play(sample.absolutePath, "Settings selection fixture", null)
            }
            compose.waitUntil(20_000) {
                manager.state.value.isPrepared && manager.state.value.positionMs >= 500 &&
                    manager.player.videoOutputFramesPerSecond > 0f
            }
            compose.runOnUiThread {
                manager.pause()
                manager.addManualSubtitle(manager.subtitleSessionId, TextSubtitles.parse(
                    "1\n00:00:00,000 --> 00:00:30,000\nPhone settings caption".toByteArray(), "phone-settings.srt"))
            }
            val phoneTrack = manager.state.value.subtitleTracks.first { it.key.startsWith("manual:") }
            val embedded = manager.state.value.subtitleTracks.first { it.key.startsWith("timedtext:") }

            for ((requested, orientation) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
            )) {
                compose.runOnUiThread { compose.activity.requestedOrientation = requested }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == orientation }
                compose.onNodeWithContentDescription(text(R.string.player_settings)).performClick()
                compose.onNodeWithText(text(R.string.video_info_title)).performScrollTo().performClick()
                compose.onNodeWithText("160 × 96").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("24 fps").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("AV1").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("AAC").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("48000 Hz").performScrollTo().assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(5504L, manager.state.value.mediaInfo.video?.bitrate)
                    assertEquals(32263L, manager.state.value.mediaInfo.audio?.bitrate)
                    assertFalse("viewing information must preserve pause", manager.state.value.isPlaying)
                }
                capture("settings-video-info-$orientation.png")
                compose.onNodeWithContentDescription(text(R.string.back)).performClick()
                compose.onNodeWithText(text(R.string.video_scale)).performScrollTo().performClick()
                choice(text(R.string.video_scale_crop)).performClick()
                compose.waitUntil(10_000) { manager.preferences.value.videoScaleMode == VideoScaleMode.CROP }
                compose.onNodeWithContentDescription(text(R.string.video_zoom)).performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
                compose.runOnIdle {
                    assertEquals(2f, manager.videoZoom.value, .01f)
                    assertFalse("video transforms must not resume paused playback", manager.state.value.isPlaying)
                }
                compose.onNodeWithText(text(R.string.video_zoom_reset)).performScrollTo().performClick()
                choice(text(R.string.video_scale_original)).performClick()
                compose.waitUntil(10_000) { manager.preferences.value.videoScaleMode == VideoScaleMode.ORIGINAL }
                choice(text(R.string.video_scale_fit)).performClick()
                compose.waitUntil(10_000) { manager.preferences.value.videoScaleMode == VideoScaleMode.FIT }
                compose.onNodeWithContentDescription(text(R.string.back)).performClick()
                compose.onNodeWithText(text(R.string.sleep_timer)).performScrollTo().performClick()
                choice(compose.activity.resources.getQuantityString(R.plurals.sleep_timer_minutes, 15, 15)).performClick()
                compose.runOnIdle {
                    assertEquals(SleepTimerMode.COUNTDOWN, manager.sleepTimerState.value.mode)
                    assertEquals(15, manager.sleepTimerState.value.minutes)
                    assertFalse(manager.state.value.isPlaying)
                }
                compose.onNodeWithText(text(R.string.sleep_timer_custom)).performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextReplacement("2")
                compose.onNodeWithText(text(R.string.sleep_timer_start)).performScrollTo().performClick()
                compose.runOnIdle { assertEquals(2, manager.sleepTimerState.value.minutes) }
                choice(text(R.string.sleep_timer_after_video)).performClick()
                compose.runOnIdle { assertEquals(SleepTimerMode.AFTER_VIDEO, manager.sleepTimerState.value.mode) }
                choice(text(R.string.sleep_timer_off)).performClick()
                compose.runOnIdle { assertEquals(SleepTimerState(), manager.sleepTimerState.value) }
                compose.onNodeWithContentDescription(text(R.string.back)).performClick()
                compose.onNodeWithText(text(R.string.player_speed)).performScrollTo().performClick()
                choice("1.5x").performClick().assertIsSelected()
                compose.runOnIdle {
                    assertEquals(1.5f, manager.state.value.playbackSpeed, .001f)
                    assertEquals("UI must update IJK's actual playback rate", 1.5f, manager.player.getSpeed(0f), .001f)
                    assertEquals(originalHoldSpeed, manager.preferences.value.longPressSpeed, .001f)
                    assertFalse("choosing speed must preserve pause", manager.state.value.isPlaying)
                }
                capture("settings-speed-$orientation.png")
                compose.onNodeWithContentDescription(text(R.string.back)).performClick()
                compose.onNode(hasText(text(R.string.player_speed)) and hasText("1.5x")).assertExists()
                compose.onNodeWithText(text(R.string.subtitle_title)).performScrollTo().performClick()
                choice(text(R.string.subtitle_off)).performClick().assertIsSelected()
                compose.runOnIdle { assertNull(manager.state.value.subtitleText) }
                choice(embedded.label).performClick().assertIsSelected()
                compose.runOnUiThread {
                    assertEquals(embedded.key, manager.state.value.selectedSubtitleKey)
                    assertEquals(embedded.key.removePrefix("timedtext:").toInt(),
                        manager.player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT))
                    manager.seekTo(1_000)
                    manager.play()
                }
                compose.waitUntil(20_000) { manager.state.value.subtitleText?.trim() == "AV1 fixture subtitle" }
                compose.runOnUiThread { manager.pause() }
                capture("settings-subtitles-$orientation.png")
                choice(phoneTrack.label).performClick().assertIsSelected()
                compose.onNodeWithText(text(R.string.subtitle_load_phone)).performScrollTo().assertIsDisplayed()
                compose.runOnIdle {
                    assertEquals(phoneTrack.key, manager.state.value.selectedSubtitleKey)
                    assertEquals("Phone settings caption", manager.state.value.subtitleText)
                }
                choice(text(R.string.subtitle_off)).performClick().assertIsSelected()
                compose.runOnIdle { assertNull(manager.state.value.subtitleText) }
                compose.onNodeWithContentDescription(text(R.string.back)).performClick()
                compose.onNode(hasText(text(R.string.subtitle_title)) and hasText(text(R.string.subtitle_off))).assertExists()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithContentDescription(text(R.string.player_speed)).performClick()
                compose.onNodeWithText("1.0x").performClick()
                compose.runOnIdle {
                    assertEquals(1f, manager.player.getSpeed(0f), .001f)
                    manager.beginTemporarySpeed(originalHoldSpeed)
                    manager.endTemporarySpeed()
                    assertEquals("hold release must restore the newly selected regular speed",
                        1f, manager.player.getSpeed(0f), .001f)
                }
            }
        } finally {
            compose.runOnUiThread {
                manager.stop()
                assertEquals("stopping must clear the previous streams' metadata", PlaybackMediaInfo(), manager.state.value.mediaInfo)
                manager.setPlaybackSpeed(1f)
                compose.activity.requestedOrientation = originalOrientation
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setPreferredTextLanguage(originalTextLanguage) }
            runBlocking { app.settingsDataStore.setVideoScaleMode(originalScaleMode) }
            sample.delete()
        }
    }

    private fun choice(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performScrollTo()

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
