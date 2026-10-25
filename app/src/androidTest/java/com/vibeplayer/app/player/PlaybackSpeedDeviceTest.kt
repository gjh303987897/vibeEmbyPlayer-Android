package com.vibeplayer.app.player

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.SurfaceView
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.test.R
import com.vibeplayer.app.ui.player.IjkPlayerView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** Native clocks AND composed-screen frame IDs; synthetic media only, no account access. */
@RunWith(AndroidJUnit4::class)
class PlaybackSpeedDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as VibePlayerApp
    private val manager get() = app.playerManager
    private val avDifference = IjkMediaPlayer::class.java.getDeclaredMethod("_getPropertyFloat",
        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }

    @Test fun hardwareAacSpeedAndPresentedFrames() = verify(R.raw.video_sample_speed_aac, ".mp4", VideoDecodeMode.HARDWARE)
    @Test fun softwareAacSpeedAndPresentedFrames() = verify(R.raw.video_sample_speed_aac, ".mp4", VideoDecodeMode.SOFTWARE)
    @Test fun hardwarePcmSpeedAndPresentedFrames() = verify(R.raw.video_sample_speed_pcm, ".mkv", VideoDecodeMode.HARDWARE)
    @Test fun softwarePcmSpeedAndPresentedFrames() = verify(R.raw.video_sample_speed_pcm, ".mkv", VideoDecodeMode.SOFTWARE)

    private fun verify(resource: Int, suffix: String, mode: VideoDecodeMode) {
        val original = runBlocking { app.settingsDataStore.playerPreferences.first() }
        val sample = File.createTempFile("speed-device-", suffix, app.cacheDir)
        instrumentation.context.resources.openRawResource(resource).use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val resultDirectory = File(app.getExternalFilesDir(null), "speed-device-results").apply { mkdirs() }
        val report = File(resultDirectory, "$mode-${if (suffix == ".mkv") "PCM" else "AAC"}.txt")
        report.writeText("Synthetic 60fps frame-ID fixture; screen ID/audio-clock comparison, not an acoustic recording.\n")
        var observer: Job? = null
        try {
            main { manager.stop(); manager.setPlaybackSpeed(1f) }
            runBlocking {
                app.settingsDataStore.setVideoDecodeMode(mode)
                app.settingsDataStore.setVideoScaleMode(VideoScaleMode.FIT)
            }
            await("decoder preference") { manager.preferences.value.videoDecodeMode == mode }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var view: IjkPlayerView
                val created = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    view = IjkPlayerView(activity, { holder, attached ->
                        manager.setVideoSurface(holder, attached)
                        if (attached) created.countDown()
                    }).apply { player = manager.player }
                    activity.setContentView(view)
                    observer = activity.lifecycleScope.launch {
                        manager.state.collect {
                            view.updateSurfaceGeneration(it.surfaceGeneration)
                            view.updateVideoSize()
                            view.videoOutputBlocked = it.videoOutputBlocked
                        }
                    }
                }
                assertTrue("native Surface", created.await(10, TimeUnit.SECONDS))
                main { manager.play(sample.absolutePath, "Synthetic speed fixture", null) }
                await("native audio/video output") {
                    manager.state.value.isPrepared && manager.state.value.isPlaying && manager.player.currentPosition > 300
                }
                assertEquals("requested decoder must actually be used", mode, manager.state.value.videoDecodeMode)
                for (rate in listOf(.5f, 1.25f, 1.5f, 2f, 3f, 4f, 1f)) {
                    main {
                        if (manager.player.currentPosition > 13_000) manager.seekTo(1_000)
                        manager.setPlaybackSpeed(rate)
                        // Keep the synthetic sine quiet without changing the system's media volume.
                        manager.player.setVolume(.03f, .03f)
                    }
                    SystemClock.sleep(1_200)
                    probe(view, rate, report)
                }
                main {
                    manager.setPlaybackSpeed(1.25f)
                    manager.beginTemporarySpeed(4f)
                    assertEquals(4f, manager.player.getSpeed(0f), .001f)
                }
                SystemClock.sleep(400)
                main { manager.endTemporarySpeed(); assertEquals(1.25f, manager.player.getSpeed(0f), .001f) }
                main { manager.pause() }
                SystemClock.sleep(300)
                val paused = main { manager.player.currentPosition }
                SystemClock.sleep(400)
                assertTrue("pause freezes the native media clock", abs(main { manager.player.currentPosition } - paused) < 100)
                main {
                    manager.beginTemporarySpeed(3f)
                    manager.endTemporarySpeed()
                    assertFalse(manager.state.value.isPlaying)
                    assertEquals(1.25f, manager.player.getSpeed(0f), .001f)
                    manager.seekTo(2_000)
                    manager.play()
                }
                SystemClock.sleep(1_200)
                probe(view, 1.25f, report)
            }
        } finally {
            main {
                observer?.cancel()
                manager.stop()
                manager.setPlaybackSpeed(1f)
                app.stopService(Intent(app, PlaybackService::class.java))
            }
            runBlocking {
                app.settingsDataStore.setVideoDecodeMode(original.videoDecodeMode)
                app.settingsDataStore.setVideoScaleMode(original.videoScaleMode)
            }
            sample.delete()
        }
    }

    private data class FrameSample(val wall: Long, val frame: Int, val position: Long, val av: Float,
                                   val fps: Float, val presentationGap: Double)

    private fun probe(view: IjkPlayerView, rate: Float, report: File) {
        val samples = (1..16).map {
            val before = main { manager.player.currentPosition }
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("screen capture unavailable")
            val bounds = main {
                val surface = view.getChildAt(0) as SurfaceView
                Rect().also { check(surface.getGlobalVisibleRect(it)) }
            }
            val frame = try {
                var number = 0
                for (bit in 0 until 16) {
                    val pixel = bitmap.getPixel(bounds.left + bounds.width() * (bit * 20 + 10) / 320,
                        bounds.top + bounds.height() * 20 / 180)
                    if (android.graphics.Color.red(pixel) > 128) number = number or (1 shl bit)
                }
                number
            } finally { bitmap.recycle() }
            val after = main { manager.player.currentPosition }
            val frameMs = frame * 1000.0 / 60
            val gap = maxOf(before - frameMs, frameMs - after, 0.0) / rate
            val sample = main { FrameSample(SystemClock.elapsedRealtime(), frame, manager.player.currentPosition,
                avDifference.invoke(manager.player, 10005, Float.NaN) as Float,
                manager.player.videoOutputFramesPerSecond, gap) }
            assertNull(manager.state.value.error)
            SystemClock.sleep(100)
            sample
        }
        val first = samples.first()
        val last = samples.last()
        val actualRate = (last.position - first.position).toDouble() / (last.wall - first.wall)
        val videoRate = (last.frame - first.frame) * 1000.0 / 60 / (last.wall - first.wall)
        val difference = samples.maxOf { abs(it.av) }
        // IJK preserves avdiff in media seconds; compare a constant wall-time tolerance at every rate.
        val avWallMs = difference * 1000 / rate
        val gap = samples.maxOf { it.presentationGap }
        val framesPerSecond = samples.takeLast(8).map { it.fps }.sorted()[4]
        val line = "rate=$rate actualRate=$actualRate screenRate=$videoRate maxAvMediaSeconds=$difference maxAvWallMs=$avWallMs maxScreenGapWallMs=$gap nativeFps=$framesPerSecond frames=${samples.map { it.frame }}\n"
        report.appendText(line)
        println(line.trim())
        assertTrue("native media clock must follow $rate: $line", abs(actualRate - rate) < rate * .20)
        assertTrue("composited screen must follow $rate: $line", abs(videoRate - rate) < rate * .25)
        assertTrue("all frame IDs must decode", samples.all { it.frame in 1..3600 })
        assertTrue("stable native A/V clocks: $line", samples.all { it.av.isFinite() && abs(it.av) / rate < .15f })
        assertTrue("screen cannot retain an old timeline: $line", gap < 200)
        assertTrue("screen cannot hold the same frame during playback: $line", samples.map { it.frame }.distinct().size >= 12)
        assertTrue("video output cannot remain at normal-speed cadence: $line", framesPerSecond >= minOf(60f * rate, 60f) * .65f)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(50)
        }
        assertTrue(message, main(condition))
    }

    private fun <T> main(block: () -> T): T {
        val result = arrayOfNulls<Any>(2)
        instrumentation.runOnMainSync { try { result[0] = block() } catch (failure: Throwable) { result[1] = failure } }
        (result[1] as Throwable?)?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }
}
