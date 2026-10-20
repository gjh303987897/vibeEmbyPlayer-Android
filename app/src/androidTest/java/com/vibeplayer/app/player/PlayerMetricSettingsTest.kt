package com.vibeplayer.app.player

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.R
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.ui.player.PlayerPerformanceReadout
import com.vibeplayer.app.ui.player.PlayerSettings
import com.vibeplayer.app.ui.player.PlayerSettingsViewModel
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerMetricSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun metricsCanBeToggledIndependentlyAndSurviveHiddenControlsAndLock() {
        val app = compose.activity.application as VibePlayerApp
        val original = runBlocking { app.settingsDataStore.playerPreferences.first().metrics }
        val originalOrientation = compose.activity.requestedOrientation
        val controls = mutableStateOf(true)
        val locked = mutableStateOf(false)
        val expected = mutableSetOf<PlayerMetric>()
        val labels = mapOf(PlayerMetric.CPU to R.string.player_metric_cpu, PlayerMetric.GPU to R.string.player_metric_gpu,
            PlayerMetric.FRAME_RATE to R.string.player_metric_fps, PlayerMetric.NETWORK_SPEED to R.string.player_metric_network)
        val prefixes = mapOf(PlayerMetric.CPU to "CPU ", PlayerMetric.GPU to "GPU ",
            PlayerMetric.FRAME_RATE to "FPS ", PlayerMetric.NETWORK_SPEED to "NET ")

        fun verify() {
            compose.waitUntil(5_000) {
                PlayerMetric.entries.all { app.playerManager.preferences.value.metrics.isEnabled(it) == (it in expected) }
            }
            val saved = runBlocking { app.settingsDataStore.playerPreferences.first().metrics }
            PlayerMetric.entries.forEach { assertEquals(it in expected, saved.isEnabled(it)) }
            val readout = compose.onNodeWithTag("player-performance-readout")
            if (expected.isEmpty()) readout.assertDoesNotExist() else {
                readout.onChildren().assertCountEquals(expected.size)
                expected.forEach { readout.onChildren().filterToOne(hasText(prefixes.getValue(it), substring = true)).assertExists() }
            }
        }
        fun toggle(metric: PlayerMetric) {
            compose.onNodeWithContentDescription(compose.activity.getString(labels.getValue(metric)))
                .performScrollTo().performClick()
            if (!expected.add(metric)) expected.remove(metric)
            verify()
        }
        try {
            runBlocking { PlayerMetric.entries.forEach { app.settingsDataStore.setPlayerMetricVisible(it, false) } }
            compose.runOnUiThread {
                val viewModel = ViewModelProvider(compose.activity)[PlayerSettingsViewModel::class.java]
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                compose.activity.setContent {
                    val preferences by viewModel.preferences.collectAsState()
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            if (controls.value && !locked.value && (preferences.showPerformance || preferences.showDiagnostics)) {
                                PlayerPerformanceReadout(viewModel, Modifier.align(Alignment.TopEnd).padding(top = 48.dp), compact = true)
                            }
                            PlayerSettings(controls.value, locked.value, preferences, viewModel)
                        }
                    }
                }
            }
            for ((requested, orientation) in listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE
            )) {
                compose.runOnUiThread { compose.activity.requestedOrientation = requested }
                compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == orientation }
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_settings)).performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.player_settings_performance)).performScrollTo().performClick()
                PlayerMetric.entries.forEach { toggle(it); toggle(it) }
                toggle(PlayerMetric.CPU)
                toggle(PlayerMetric.NETWORK_SPEED)
                val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    File(compose.activity.getExternalFilesDir(null), "independent-metrics-$orientation.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
                Espresso.pressBack()
                compose.runOnUiThread { controls.value = false }
                verify()
                compose.runOnUiThread { controls.value = true; locked.value = true }
                verify()
                compose.runOnUiThread { locked.value = false }
                compose.onNodeWithContentDescription(compose.activity.getString(R.string.player_settings)).performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.player_settings_performance)).performScrollTo().performClick()
                toggle(PlayerMetric.CPU)
                toggle(PlayerMetric.NETWORK_SPEED)
                Espresso.pressBack()
            }
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = originalOrientation }
            runBlocking {
                PlayerMetric.entries.forEach { app.settingsDataStore.setPlayerMetricVisible(it, original.isEnabled(it)) }
            }
        }
    }
}
