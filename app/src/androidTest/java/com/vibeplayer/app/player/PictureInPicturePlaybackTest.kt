package com.vibeplayer.app.player

import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.player.LocalPlayerPip
import com.vibeplayer.app.ui.player.rememberPlayerPictureInPictureState
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic-media device regression; no service credentials or user videos are used. */
@RunWith(AndroidJUnit4::class)
class PictureInPicturePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun manualEntryKeepsPlayingExpansionKeepsTheSessionAndClosingPauses() {
        val app = instrumentation.targetContext.applicationContext as VibePlayerApp
        assumeTrue(app.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE))
        val manager = app.playerManager
        val sample = File.createTempFile("pip-regression-", ".mp4", app.cacheDir)
        instrumentation.context.resources.openRawResource(com.vibeplayer.app.test.R.raw.video_sample_controls)
            .use { input -> sample.outputStream().use { input.copyTo(it) } }
        lateinit var activity: MainActivity
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { current ->
                    activity = current
                    manager.stop()
                    current.setContent {
                        CompositionLocalProvider(LocalPlayerPip provides current.pictureInPicture) {
                            rememberPlayerPictureInPictureState()
                            val state by manager.state.collectAsState()
                            MaterialTheme {
                                Box(Modifier.fillMaxSize()) {
                                    AndroidView(factory = { context ->
                                        IjkPlayerView(context, manager::setVideoSurface).apply {
                                            player = manager.player
                                            onVideoBoundsChanged = current.pictureInPicture::updateSourceBounds
                                        }
                                    }, update = {
                                        it.updateSurfaceGeneration(state.surfaceGeneration)
                                        if (state.isPrepared) it.updateVideoSize()
                                        it.updatePresentation(VideoScaleMode.FIT, 1f)
                                    }, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    manager.play(sample.absolutePath, "PiP synthetic fixture", null)
                }
                await { manager.state.value.isPrepared && manager.state.value.positionMs > 500 }
                assumeTrue("System PiP permission must be enabled for this test",
                    activity.pictureInPicture.state.value.entryBlock != PipEntryBlock.PERMISSION)
                onMain { manager.setSleepTimer(15); manager.setVideoZoom(2f) }
                val start = manager.state.value.positionMs
                onMain { assertTrue(activity.pictureInPicture.enter()) }
                await { activity.isInPictureInPictureMode && activity.pictureInPicture.state.value.inPictureInPicture }
                await { manager.state.value.isPlaying && manager.state.value.positionMs > start + 1_000 }
                onMain {
                    assertTrue(activity.pictureInPicture.state.value.hideChrome)
                    assertEquals(15, manager.sleepTimerState.value.minutes)
                    assertEquals(2f, manager.videoZoom.value, .001f)
                    activity.startActivity(Intent(activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                await { !activity.isInPictureInPictureMode && !activity.pictureInPicture.state.value.hideChrome }
                onMain {
                    assertTrue(manager.playWhenReady)
                    assertEquals(2f, manager.videoZoom.value, .001f)
                    assertTrue(activity.pictureInPicture.enter())
                }
                await { activity.isInPictureInPictureMode }
                onMain { activity.finish() }
                await { !manager.state.value.isPlaying && !manager.playWhenReady }
            }
        } finally {
            onMain { manager.stop(); app.stopService(Intent(app, PlaybackService::class.java)) }
            sample.delete()
        }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition()) {
            assertTrue("PiP lifecycle condition must complete", SystemClock.elapsedRealtime() < deadline)
            SystemClock.sleep(50)
        }
    }
}
