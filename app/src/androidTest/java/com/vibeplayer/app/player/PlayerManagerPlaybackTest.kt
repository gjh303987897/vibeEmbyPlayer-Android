package com.vibeplayer.app.player

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.test.R
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.local.LocalPlayerViewModel
import com.vibeplayer.app.ui.navigation.Routes
import com.vibeplayer.app.model.PlaybackSource
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.IjkMediaMeta
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * Exercises the application's singleton, listeners, MediaCodec options, focus and foreground
 * service with a real video Surface. The older audio fixtures contain no video streams, so these
 * fixtures add generated H.264 colour bars while retaining their AC-3 / DTS audio unchanged.
 */
@RunWith(AndroidJUnit4::class)
class PlayerManagerPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var surfaceObserver: Job? = null

    @Test fun localQueueUsesNativeEofAndHonoursAutoplayAndSleepTimer() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val manager = app.playerManager
        val originalAuto = runBlocking { app.settingsDataStore.playerPreferences.first().autoPlayNext }
        val samples = (1..3).map { copyResource(R.raw.video_sample_controls, ".mp4") }
        var viewModel: LocalPlayerViewModel? = null
        try {
            runBlocking { app.settingsDataStore.setAutoPlayNext(true) }
            awaitCondition("autoplay preference must load") { manager.preferences.value.autoPlayNext }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val surface = attachSurface(scenario, manager)
                scenario.onActivity { activity ->
                    val localPlayer = ViewModelProvider(activity)[LocalPlayerViewModel::class.java]
                    viewModel = localPlayer
                    val entries = samples.mapIndexed { index, file -> PlaybackQueueItem(file.absolutePath, "Queue ${index + 1}.mp4") }
                    manager.queue.stage(PlaybackQueueScope(PlaybackSource.LOCAL), entries, entries[0].id)
                    localPlayer.play(Routes.localPlayer(entries[0].id).substringAfterLast('/'))
                }
                awaitCondition("first queued local video must prepare") {
                    manager.state.value.isPrepared && manager.state.value.title == "Queue 1.mp4"
                }
                callOnMain {
                    manager.pause()
                    manager.setSleepTimer(15)
                    manager.seekTo(manager.state.value.durationMs - 350)
                }
                SystemClock.sleep(1_000)
                callOnMain { assertEquals("paused seek near EOF must not advance", 0, manager.queue.state.value.index) }
                callOnMain { manager.play() }
                awaitCondition("actual native EOF must advance into the second video") {
                    manager.queue.state.value.index == 1 && manager.state.value.isPrepared &&
                        manager.state.value.title == "Queue 2.mp4" && manager.state.value.positionMs > 500
                }
                callOnMain {
                    assertEquals(SleepTimerMode.COUNTDOWN, manager.sleepTimerState.value.mode)
                    assertTrue(manager.sleepTimerState.value.remainingMs in 1..900_000L)
                }
                awaitColourBarsVisible(surface)
                callOnMain {
                    manager.setSleepAfterVideo()
                    manager.seekTo(manager.state.value.durationMs - 350)
                }
                awaitCondition("stop-after-video must expire before autoplay") { manager.sleepTimerState.value.expired }
                callOnMain {
                    assertEquals(1, manager.queue.state.value.index)
                    assertFalse(manager.state.value.isPlaying)
                    assertTrue(manager.queue.state.value.hasNext)
                    manager.queue.previous()
                }
                awaitCondition("manual previous must remain available") {
                    manager.state.value.isPrepared && manager.state.value.title == "Queue 1.mp4"
                }
                runBlocking { app.settingsDataStore.setAutoPlayNext(false) }
                awaitCondition("autoplay off must reach manager") { !manager.preferences.value.autoPlayNext }
                callOnMain {
                    manager.seekTo(manager.state.value.durationMs - 350)
                    manager.play()
                }
                try {
                    awaitCondition("disabled autoplay must leave completed video paused") {
                        manager.state.value.positionMs >= manager.state.value.durationMs - 100 && !manager.state.value.isPlaying
                    }
                } finally {
                    android.util.Log.i("QueueDeviceTest", "EOF position=${manager.state.value.positionMs}, duration=${manager.state.value.durationMs}, playing=${manager.state.value.isPlaying}, prepared=${manager.state.value.isPrepared}, queue=${manager.queue.state.value.index}")
                }
                callOnMain {
                    assertEquals(0, manager.queue.state.value.index)
                    manager.queue.next()
                    manager.queue.previous()
                    manager.queue.next()
                }
                awaitCondition("rapid selections must finish on the latest video") {
                    manager.queue.state.value.index == 1 && manager.state.value.isPrepared &&
                        manager.state.value.title == "Queue 2.mp4"
                }
                callOnMain {
                    manager.queue.next()
                    manager.stop()
                }
                SystemClock.sleep(1_000)
                callOnMain {
                    assertTrue("stop must detach queue and cancel source preparation", manager.queue.state.value.items.isEmpty())
                    assertFalse(manager.state.value.isPrepared)
                    assertFalse(manager.state.value.isPlaying)
                }
            }
        } finally {
            callOnMain {
                viewModel?.stopPlayback()
                surfaceObserver?.cancel()
                manager.queue.clear()
                manager.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setAutoPlayNext(originalAuto) }
            samples.forEach { it.delete() }
        }
    }

    @Test fun sameVideoReloadKeepsSleepDeadlineAndZoomAndNewVideoClearsThem() {
        val context = instrumentation.targetContext
        val manager = (context.applicationContext as VibePlayerApp).playerManager
        val sample = copyResource(R.raw.video_sample_subtitle_timing, ".mp4")
        try {
            callOnMain { manager.stop() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                attachSurface(scenario, manager)
                callOnMain { manager.play(sample.absolutePath, "Session controls", null) }
                awaitCondition("initial video must prepare") { manager.state.value.isPrepared }
                callOnMain {
                    manager.pause()
                    manager.setSleepTimer(15)
                    manager.setVideoZoom(2f)
                    manager.play(sample.absolutePath, "Same channel, another route", null,
                        playWhenReady = false, preserveSessionControls = true)
                    assertEquals(SleepTimerMode.COUNTDOWN, manager.sleepTimerState.value.mode)
                    assertEquals(2f, manager.videoZoom.value, .001f)
                }
                awaitCondition("same-session reload must prepare paused") { manager.state.value.isPrepared }
                callOnMain {
                    assertFalse(manager.state.value.isPlaying)
                    assertEquals(15, manager.sleepTimerState.value.minutes)
                    assertTrue(manager.sleepTimerState.value.remainingMs in 1..900_000L)
                    manager.play(sample.absolutePath, "Another video", null, playWhenReady = false)
                    assertEquals(SleepTimerState(), manager.sleepTimerState.value)
                    assertEquals(1f, manager.videoZoom.value, .001f)
                }
            }
        } finally {
            callOnMain {
                surfaceObserver?.cancel()
                manager.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            sample.delete()
        }
    }

    @Test
    fun subtitleTimingShiftsEmbeddedAndImportedCuesAndResetsForTheNextVideo() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val manager = app.playerManager
        val originalLanguage = runBlocking { app.settingsDataStore.preferredTextLanguage.first() }
        val sample = copyResource(R.raw.video_sample_subtitle_timing, ".mp4")
        try {
            callOnMain { manager.stop() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val view = attachSurface(scenario, manager)
                callOnMain { manager.play(sample.absolutePath, "Subtitle timing", null) }
                awaitCondition("timed text must be available") {
                    assertNull(manager.state.value.error)
                    manager.state.value.isPrepared && manager.state.value.subtitleTracks.isNotEmpty()
                }
                callOnMain { manager.selectSubtitle(manager.state.value.subtitleTracks.first(), persist = false) }
                for ((offset, position, caption) in listOf(
                    Triple(2_000L, 5_000L, "Timing first"),
                    Triple(-2_000L, 1_000L, "Timing first"),
                    Triple(0L, 6_500L, "Timing second")
                )) {
                    callOnMain {
                        manager.pause()
                        manager.setSubtitleDelay(offset)
                        manager.seekTo(position)
                        manager.play()
                    }
                    awaitCondition("embedded caption must follow $offset ms offset") {
                        assertNull(manager.state.value.error)
                        manager.state.value.subtitleText?.trim() == caption &&
                            manager.state.value.positionMs >= position && manager.player.videoOutputFramesPerSecond > 0f
                    }
                    assertColourBarsVisible(view)
                }
                callOnMain {
                    manager.pause()
                    manager.addManualSubtitle(manager.subtitleSessionId,
                        com.vibeplayer.app.domain.subtitle.TextSubtitles.parse(
                            "1\n00:00:02,000 --> 00:00:04,000\nImported timing".toByteArray(), "timing.srt"))
                    manager.seekTo(3_000)
                    assertEquals("Imported timing", manager.state.value.subtitleText)
                    manager.setSubtitleDelay(2_000)
                    assertNull(manager.state.value.subtitleText)
                    manager.setSubtitleDelay(0)
                    assertEquals("Imported timing", manager.state.value.subtitleText)
                    assertFalse("timing changes must preserve pause", manager.state.value.isPlaying)
                    manager.setSubtitleDelay(-2_000)
                    manager.play(sample.absolutePath, "Next video", null)
                    assertEquals("opening another source must clear its predecessor's offset", 0L, manager.state.value.subtitleDelayMs)
                }
                awaitCondition("next video must prepare with its default timing") {
                    manager.state.value.isPrepared && manager.state.value.subtitleDelayMs == 0L
                }
            }
        } finally {
            callOnMain {
                surfaceObserver?.cancel()
                manager.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setPreferredTextLanguage(originalLanguage) }
            sample.delete()
        }
    }

    @Test
    fun av1RendersWithAudioAndSubtitlesInBothDecoderModesAndThenPlaysH264() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val original = shared.preferences.value.videoDecodeMode
        val av1 = copyResource(R.raw.video_sample_av1, ".mp4")
        val h264 = copyResource(R.raw.video_sample_controls, ".mp4")
        try {
            callOnMain { shared.stop() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val view = attachSurface(scenario, shared)
                for (mode in VideoDecodeMode.entries) {
                    callOnMain { shared.stop() }
                    runBlocking { app.settingsDataStore.setVideoDecodeMode(mode) }
                    awaitCondition("$mode preference must be loaded") { shared.preferences.value.videoDecodeMode == mode }
                    callOnMain { shared.play(av1.absolutePath, "AV1 colour bars $mode", null) }
                    awaitCondition("AV1 must open a video decoder, audio and subtitles in $mode") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPrepared &&
                            shared.player.videoDecoder != IjkMediaPlayer.FFP_PROPV_DECODER_UNKNOWN &&
                            shared.state.value.audioTracks.isNotEmpty() && shared.state.value.subtitleTracks.isNotEmpty()
                    }
                    callOnMain {
                        // Do not depend on the user's saved subtitle language or "off" preference.
                        shared.selectSubtitle(shared.state.value.subtitleTracks.first(), persist = false)
                        // The short local fixture can already be fully demuxed while text is
                        // disabled by the saved preference. Refill the selected subtitle queue.
                        shared.seekTo(500)
                        assertEquals("embedded subtitle stream must be selected", 2,
                            shared.player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT))
                    }
                    awaitCondition("AV1 must advance, render frames and display its embedded caption in $mode") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                            shared.player.videoOutputFramesPerSecond > 0f &&
                            // FFmpeg's mov_text decoder emits ASS dialogue with CR/LF suffixes.
                            shared.state.value.subtitleText?.trim() == "AV1 fixture subtitle"
                    }
                    val av1Decoder = callOnMain {
                        val decoder = shared.player.videoDecoder
                        assertTrue("AV1 must use a real video decoder ($mode: $decoder)",
                            decoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC ||
                                decoder == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC)
                        if (mode == VideoDecodeMode.SOFTWARE) {
                            assertEquals("software mode must decode AV1 on the client",
                                IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC, decoder)
                        }
                        val audio = shared.player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)
                        assertEquals("aac", shared.player.trackInfo?.getOrNull(audio)?.format
                            ?.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME))
                        assertTrue("AAC audio decoder must remain active",
                            !shared.player.mediaInfo?.mAudioDecoder.isNullOrBlank())
                        assertEquals(mode, shared.preferences.value.videoDecodeMode)
                        decoder
                    }
                    assertColourBarsVisible(view)
                    val generation = shared.state.value.surfaceGeneration
                    // Keep the same view: a software AV1 fallback can own its BufferQueue even
                    // though the saved preference still requests hardware for the next movie.
                    callOnMain { shared.play(h264.absolutePath, "H264 after AV1 $mode", null) }
                    awaitCondition("H264 must render after AV1 in $mode") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                            shared.player.videoOutputFramesPerSecond > 0f &&
                            shared.player.videoDecoder != IjkMediaPlayer.FFP_PROPV_DECODER_UNKNOWN
                    }
                    if (mode == VideoDecodeMode.HARDWARE && av1Decoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC) {
                        assertTrue("hardware preparation after AV1 fallback needs a fresh Surface",
                            shared.state.value.surfaceGeneration > generation)
                    }
                    assertColourBarsVisible(view)
                }
            }
        } catch (failure: AssertionError) {
            val state = shared.state.value
            throw AssertionError("AV1 failure: mode=${shared.preferences.value.videoDecodeMode}, prepared=${state.isPrepared}, playing=${state.isPlaying}, position=${state.positionMs}, subtitle=${state.subtitleText}, decoder=${shared.player.videoDecoder}, fps=${shared.player.videoOutputFramesPerSecond}, error=${state.error}", failure)
        } finally {
            callOnMain {
                surfaceObserver?.cancel()
                shared.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(original) }
            av1.delete()
            h264.delete()
        }
    }

    @Test
    fun videoPreparationWaitsForSurfaceWhenViewArrivesAfterPlaybackRequest() {
        val context = instrumentation.targetContext
        val shared = (context.applicationContext as VibePlayerApp).playerManager
        val sample = copyResource(R.raw.video_sample_controls, ".mp4")
        try {
            callOnMain { shared.stop() }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val previous = attachSurface(scenario, shared)
                val previousSurface = callOnMain { previous.getChildAt(0) as SurfaceView }
                scenario.onActivity { activity -> activity.setContentView(FrameLayout(activity)) }
                awaitCondition("previous Surface must be destroyed before the new request") {
                    !previousSurface.holder.surface.isValid
                }
                callOnMain {
                    shared.beginLoading("Waiting for initial view")
                    shared.play(sample.absolutePath, "Delayed Surface", null)
                }
                // Deliberately keep a local, immediately readable source without a Surface.
                // Preparing now would configure IJK's dummy video output before the UI arrives.
                SystemClock.sleep(500)
                callOnMain {
                    assertFalse("native preparation must wait for a real Surface", shared.state.value.isPrepared)
                    assertFalse(shared.state.value.isPlaying)
                    assertTrue(shared.state.value.buffering)
                    assertNull(shared.state.value.error)
                }
                val current = attachSurface(scenario, shared)
                awaitCondition("attaching the delayed view must start visible video playback") {
                    assertNull(shared.state.value.error)
                    shared.state.value.isPrepared && shared.state.value.isPlaying &&
                        shared.state.value.positionMs >= MIN_POSITION_MS &&
                        shared.player.videoOutputFramesPerSecond > 0f
                }
                assertColourBarsVisible(current)
            }
        } finally {
            callOnMain {
                surfaceObserver?.cancel()
                shared.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            sample.delete()
        }
    }

    @Test
    fun automaticSoftwareFallbackThenHardwareReplacesSurfaceAcrossSourceChanges() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val original = shared.preferences.value.videoDecodeMode
        val software = copyResource(R.raw.video_sample_high10, ".mp4")
        val hardware = copyResource(R.raw.video_sample_controls, ".mp4")
        try {
            callOnMain { shared.stop() }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.HARDWARE) }
            awaitCondition("hardware preference must be loaded") {
                shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                // Keep this IjkPlayerView throughout: replacing the entire view between files
                // would hide the stale CPU BufferQueue by supplying a fresh Surface implicitly.
                val view = attachSurface(scenario, shared)
                callOnMain { shared.play(hardware.absolutePath, "Hardware baseline", null) }
                awaitCondition("baseline must render") {
                    assertNull(shared.state.value.error)
                    shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                        shared.player.videoOutputFramesPerSecond > 0f
                }
                assumeTrue("device needs an AVC MediaCodec to exercise CPU-to-MediaCodec ownership",
                    callOnMain { shared.player.videoDecoder } == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC)
                assertColourBarsVisible(view)

                for (sourceChange in listOf("repeated loading", "direct", "superseded replacement")) {
                    callOnMain { shared.play(software.absolutePath, "Automatic High 10 fallback", null) }
                    awaitCondition("High 10 must fall back to AVCodec without changing the preference") {
                        assertNull(shared.state.value.error)
                        shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE &&
                            shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                            shared.player.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC &&
                            shared.player.videoOutputFramesPerSecond > 0f
                    }
                    assertColourBarsVisible(view)
                    val oldSurface = callOnMain { view.getChildAt(0) as SurfaceView }
                    val oldGeneration = shared.state.value.surfaceGeneration
                    callOnMain {
                        if (sourceChange == "repeated loading") {
                            // ViewModel initialization and play() both begin loading. The second
                            // reset must retain the old Surface's CPU ownership and pending refresh.
                            shared.beginLoading("First metadata preparation")
                            shared.beginLoading("Second metadata preparation")
                        }
                        shared.play(hardware.absolutePath, "Hardware after software", null)
                        if (sourceChange == "superseded replacement") {
                            // Main has not yielded to Compose yet: the first play requested a new
                            // generation, but its Surface has not been created. A new loading
                            // request cancels that deferred and must wait for the same replacement.
                            assertTrue(shared.state.value.surfaceGeneration > oldGeneration)
                            shared.beginLoading("Superseding pending Surface")
                            shared.play(hardware.absolutePath, "Latest hardware request", null)
                            assertFalse(shared.state.value.isPrepared)
                        }
                    }
                    awaitCondition("source change must replace the CPU-owned Surface ($sourceChange)") {
                        val current = view.getChildAt(0) as SurfaceView
                        shared.state.value.surfaceGeneration > oldGeneration && current !== oldSurface &&
                            current.holder.surface.isValid
                    }
                    awaitCondition("new Surface must render using MediaCodec ($sourceChange)") {
                        assertNull(shared.state.value.error)
                        shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE &&
                            shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                            shared.player.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC &&
                            shared.player.videoOutputFramesPerSecond > 0f
                    }
                    if (sourceChange == "superseded replacement") {
                        assertEquals("Latest hardware request", shared.state.value.title)
                    }
                    assertColourBarsVisible(view)
                }
            }
        } finally {
            callOnMain {
                surfaceObserver?.cancel()
                shared.stop()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(original) }
            software.delete()
            hardware.delete()
        }
    }

    @Test
    fun encryptedSeekShutterHidesOldVideoUntilTargetRenders() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val originalMode = runBlocking { app.settingsDataStore.playerPreferences.first().videoDecodeMode }
        fun visibleCenter(view: IjkPlayerView): Int {
            val point = IntArray(2)
            callOnMain {
                view.getLocationOnScreen(point)
                point[0] += view.width / 2
                point[1] += view.height / 2
            }
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            return try { screen.getPixel(point[0], point[1]) } finally { screen.recycle() }
        }
        fun awaitScreenColor(view: IjkPlayerView, message: String, matches: (Int) -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            do {
                if (matches(visibleCenter(view))) return
                SystemClock.sleep(32)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw AssertionError(message)
        }
        try {
            for (mode in VideoDecodeMode.entries) {
                runBlocking { app.settingsDataStore.setVideoDecodeMode(mode) }
                awaitCondition("decoder preference must load") { shared.preferences.value.videoDecodeMode == mode }
                WebDavTsslSeekFixture(context, instrumentation.context, "segment_012.ts").use { fixture ->
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        val view = attachSurface(scenario, shared)
                        val playback = fixture.prepare()
                        callOnMain {
                            shared.play(playback.playUrl, "Synthetic encrypted seek shutter", null,
                                interruptPendingReads = playback::interruptPendingReads)
                        }
                        awaitCondition("initial video must play") { shared.state.value.isPlaying && shared.state.value.positionMs > 500 }
                        awaitScreenColor(view, "the uncovered screen must show the red source") {
                            Color.red(it) > 150 && Color.blue(it) < 50
                        }
                        val originalSurface = callOnMain { (view.getChildAt(0) as SurfaceView).holder.surface }
                        callOnMain {
                            shared.seekTo(24_000)
                            assertTrue(shared.state.value.videoOutputBlocked)
                        }
                        assertTrue("target segment must be delayed", fixture.slowRead.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        awaitCondition("view must cover stale native output") { view.videoOutputBlocked }
                        repeat(2) {
                            SystemClock.sleep(250)
                            val covered = visibleCenter(view)
                            assertTrue("old moving video must not remain visible", Color.red(covered) < 8 && Color.green(covered) < 8 && Color.blue(covered) < 8)
                            callOnMain {
                                assertEquals(24_000L, shared.state.value.positionMs)
                                assertTrue(shared.state.value.buffering)
                                assertTrue(originalSurface === (view.getChildAt(0) as SurfaceView).holder.surface)
                            }
                        }
                        awaitCondition("fresh output must uncover the target") {
                            shared.state.value.isPlaying && !shared.state.value.videoOutputBlocked && !view.videoOutputBlocked
                        }
                        awaitScreenColor(view, "the target blue segment must be visible") {
                            Color.blue(it) > 150 && Color.red(it) < 50
                        }
                        callOnMain { shared.pause(); shared.seekTo(4_000); shared.seekTo(22_000) }
                        awaitCondition("paused seek must reveal its target without waiting for audio") {
                            !shared.state.value.videoOutputBlocked && !view.videoOutputBlocked &&
                                !shared.state.value.buffering && shared.state.value.positionMs == 22_000L
                        }
                        callOnMain { assertFalse(shared.playWhenReady); assertFalse(shared.player.isPlaying) }
                        assertBlueFrameVisible(view)
                        callOnMain { shared.seekTo(24_000); shared.stop() }
                        awaitCondition("stopping must clear the shutter") { !shared.state.value.videoOutputBlocked && !view.videoOutputBlocked }
                    }
                }
            }
        } finally {
            callOnMain { shared.stop(); surfaceObserver?.cancel(); context.stopService(Intent(context, PlaybackService::class.java)) }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(originalMode) }
        }
    }

    @Test
    fun webDavTsslSeekInterruptsSlowOldSegmentAndKeepsLatestPausedTarget() {
        val context = instrumentation.targetContext
        val shared = (context.applicationContext as VibePlayerApp).playerManager
        WebDavTsslSeekFixture(context, instrumentation.context).use { fixture ->
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val surface = attachSurface(scenario, shared)
                    callOnMain {
                        shared.beginLoading("TSSL startup")
                        assertTrue("loading must start before WebDAV/container preparation", shared.state.value.buffering)
                    }
                    val playback = fixture.prepare()
                    callOnMain {
                        assertTrue(shared.state.value.buffering)
                        shared.play(playback.playUrl, "TSSL seek", null,
                            networkByteCounter = playback.networkByteCounter,
                            interruptPendingReads = playback::interruptPendingReads)
                    }
                    awaitCondition("encrypted video must start") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPlaying && shared.state.value.positionMs > 200
                    }
                    assertTrue("old segment must be blocked in WebDAV", fixture.slowRead.await(5, TimeUnit.SECONDS))
                    val started = SystemClock.elapsedRealtime()
                    callOnMain {
                        shared.seekTo(24_000)
                        assertEquals(24_000, shared.state.value.positionMs)
                        assertTrue(shared.state.value.buffering)
                        assertFalse(shared.state.value.isPlaying)
                    }
                    awaitCondition("seek must reach the target and resume") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPlaying && shared.player.currentPosition in 23_000..26_500
                    }
                    var lastFrameError: AssertionError? = null
                    do {
                        try {
                            assertBlueFrameVisible(surface)
                            lastFrameError = null
                            break
                        } catch (failure: AssertionError) { lastFrameError = failure }
                        SystemClock.sleep(32)
                    } while (SystemClock.elapsedRealtime() - started < 4_000)
                    lastFrameError?.let { throw it }
                    val elapsed = SystemClock.elapsedRealtime() - started
                    println("TSSL seek rendered blue target in ${elapsed}ms, native=${shared.player.currentPosition}")
                    assertTrue("seek must not wait for the old eight-second response", elapsed < 4_000)
                    callOnMain {
                        shared.pause()
                        shared.seekTo(4_000)
                        shared.seekTo(22_000)
                        assertEquals(22_000, shared.state.value.positionMs)
                    }
                    awaitCondition("rapid seeks must keep the last target paused") {
                        assertNull(shared.state.value.error)
                        !shared.state.value.buffering && !shared.state.value.isPlaying &&
                            shared.state.value.positionMs == 22_000L
                    }
                    SystemClock.sleep(300)
                    callOnMain {
                        assertFalse(shared.playWhenReady)
                        assertFalse(shared.player.isPlaying)
                        assertEquals(22_000, shared.state.value.positionMs)
                        shared.stop()
                        shared.beginLoading()
                        shared.failLoading("Synthetic prepare error")
                        assertFalse(shared.state.value.buffering)
                        assertEquals("Synthetic prepare error", shared.state.value.error)
                    }
                }
            } finally {
                callOnMain {
                    surfaceObserver?.cancel()
                    shared.stop()
                    context.stopService(Intent(context, PlaybackService::class.java))
                }
            }
        }
    }

    @Test
    fun webDavTsslSeeksWaitForBothOutputsAndKeepAudioVideoClocksAligned() {
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val originalMode = runBlocking { app.settingsDataStore.playerPreferences.first().videoDecodeMode }
        val clockReport = File(context.getExternalFilesDir(null), "tssl-seek-clocks.txt")
        clockReport.writeText("Synthetic TSSL seek clock samples (seconds)\n")
        // IJK exposes A-V difference natively but its pinned Java wrapper has no public getter.
        // Reflection stays in instrumentation; production uses actual seek-rendering events.
        val property = IjkMediaPlayer::class.java.getDeclaredMethod("_getPropertyFloat",
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
        val infoField = tv.danmaku.ijk.media.player.AbstractMediaPlayer::class.java
            .getDeclaredField("mOnInfoListener").apply { isAccessible = true }
        try {
            for (mode in VideoDecodeMode.entries) {
                callOnMain { shared.stop() }
                runBlocking { app.settingsDataStore.setVideoDecodeMode(mode) }
                awaitCondition("decoder preference must load") { shared.preferences.value.videoDecodeMode == mode }
                WebDavTsslSeekFixture(context, instrumentation.context).use { fixture ->
                    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                        val surface = attachSurface(scenario, shared)
                        val playback = fixture.prepare()
                        callOnMain {
                            shared.play(playback.playUrl, "Synthetic TSSL A/V seek", null,
                                networkByteCounter = playback.networkByteCounter,
                                interruptPendingReads = playback::interruptPendingReads)
                        }
                        awaitCondition("TSSL audio/video must start") {
                            shared.state.value.isPlaying && shared.state.value.positionMs > 200 &&
                                shared.state.value.audioTracks.isNotEmpty()
                        }
                        var audioRendered = false
                        var videoRendered = false
                        var observingSeek = false
                        var exposedBeforeReady = false
                        val originalInfo = callOnMain { infoField.get(shared.player) as tv.danmaku.ijk.media.player.IMediaPlayer.OnInfoListener }
                        var stateObserver: Job? = null
                        callOnMain {
                            shared.player.setOnInfoListener { player, what, extra ->
                                if (what == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_AUDIO_SEEK_RENDERING_START) audioRendered = true
                                if (what == tv.danmaku.ijk.media.player.IMediaPlayer.MEDIA_INFO_VIDEO_SEEK_RENDERING_START) videoRendered = true
                                originalInfo.onInfo(player, what, extra)
                            }
                            scenario.onActivity { activity ->
                                stateObserver = activity.lifecycleScope.launch {
                                    shared.state.collect { state ->
                                        if (observingSeek && state.isPlaying && (!audioRendered || !videoRendered)) exposedBeforeReady = true
                                    }
                                }
                            }
                        }
                        fun beginProbe() {
                            audioRendered = false
                            videoRendered = false
                            exposedBeforeReady = false
                            observingSeek = true
                        }
                        fun awaitOutputAndCheckClocks(target: Long) {
                            awaitCondition("$mode seek must render audio AND video at $target") {
                                assertNull(shared.state.value.error)
                                audioRendered && videoRendered && shared.state.value.isPlaying &&
                                    !shared.state.value.buffering && shared.player.currentPosition in (target - 1000)..(target + 4000)
                            }
                            SystemClock.sleep(500)
                            val clockDeadline = SystemClock.elapsedRealtime() + 1_000
                            while (SystemClock.elapsedRealtime() < clockDeadline) {
                                val difference = callOnMain { property.invoke(shared.player, 10005, Float.NaN) as Float }
                                if (difference.isFinite() && kotlin.math.abs(difference) <= .25f) break
                                SystemClock.sleep(50)
                            }
                            val differences = (1..12).map {
                                val difference = callOnMain { property.invoke(shared.player, 10005, Float.NaN) as Float }
                                SystemClock.sleep(100)
                                difference
                            }
                            println("TSSL $mode seek=$target, A-V differences=$differences")
                            clockReport.appendText("$mode targetMs=$target A-V=$differences\n")
                            assertTrue("$mode seek must keep both output clocks within 250ms: $differences",
                                differences.all { it.isFinite() && kotlin.math.abs(it) <= .25f })
                            callOnMain {
                                assertFalse("playback must not be exposed before both outputs are ready", exposedBeforeReady)
                                observingSeek = false
                            }
                        }
                        try {
                            for (target in listOf(23_750L, 12_250L, 18_500L)) {
                                callOnMain { beginProbe(); shared.seekTo(target) }
                                awaitOutputAndCheckClocks(target)
                            }
                            callOnMain {
                                shared.pause()
                                beginProbe()
                                shared.seekTo(4_000)
                                shared.seekTo(22_000)
                            }
                            awaitCondition("paused rapid seeks must retain latest target") {
                                !shared.state.value.isPlaying && !shared.state.value.buffering &&
                                    shared.state.value.positionMs == 22_000L
                            }
                            callOnMain { assertFalse(shared.playWhenReady); shared.play() }
                            awaitOutputAndCheckClocks(22_000)
                            assertBlueFrameVisible(surface)
                            callOnMain { shared.seekTo(18_000); shared.stop() }
                            SystemClock.sleep(250)
                            callOnMain { assertFalse(shared.state.value.isPlaying); assertFalse(shared.state.value.isPrepared) }
                        } finally {
                            callOnMain {
                                stateObserver?.cancel()
                                shared.player.setOnInfoListener(originalInfo)
                                shared.stop()
                                surfaceObserver?.cancel()
                            }
                        }
                    }
                }
            }
        } finally {
            callOnMain { shared.stop(); context.stopService(Intent(context, PlaybackService::class.java)) }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(originalMode) }
        }
    }

    /** Opt-in only: an explicitly selected user source; no accounts or keys leave the app. */
    @Test
    fun originalTsslLoopbackSeekClockProbe() {
        val arguments = InstrumentationRegistry.getArguments()
        val port = arguments.getString("originalTsslPort")?.toIntOrNull()
        val serviceName = arguments.getString("originalTsslService")
        val path = arguments.getString("originalTsslPath")
        val configuredSource = serviceName != null && path?.endsWith(".m3u8sp", ignoreCase = true) == true
        assumeTrue("requires an explicitly selected TSSL source", configuredSource || (port != null && port in 1..65535))
        val context = instrumentation.targetContext
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val originalMode = runBlocking { app.settingsDataStore.playerPreferences.first().videoDecodeMode }
        val report = File(context.getExternalFilesDir(null), "original-tssl-seek-clocks.txt")
        val property = IjkMediaPlayer::class.java.getDeclaredMethod("_getPropertyFloat",
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }
        report.writeText(if (configuredSource) "Configured WebDAV TSSL output-clock probe.\n"
            else "Original TSSL loopback output-clock probe; upstream cancellation is not exercised.\n")
        var configuredPlayback: com.vibeplayer.app.player.hls.EncryptedHlsPlayback? = null
        fun record(line: String) = synchronized(report) { report.appendText(line + "\n") }
        val infoField = tv.danmaku.ijk.media.player.AbstractMediaPlayer::class.java
            .getDeclaredField("mOnInfoListener").apply { isAccessible = true }
        val originalInfo = callOnMain { infoField.get(shared.player) as tv.danmaku.ijk.media.player.IMediaPlayer.OnInfoListener }
        callOnMain {
            shared.player.setOnInfoListener { player, what, extra ->
                record("info=$what extra=$extra nativeMs=${shared.player.currentPosition}")
                originalInfo.onInfo(player, what, extra)
            }
        }
        fun awaitOriginal(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 90_000
            var nextSample = 0L
            do {
                if (callOnMain { assertNull(shared.state.value.error); condition() }) return
                if (SystemClock.elapsedRealtime() >= nextSample) {
                    record(callOnMain {
                        "waiting: nativeMs=${shared.player.currentPosition}, nativePlaying=${shared.player.isPlaying}, " +
                            "intent=${shared.playWhenReady}, statePlaying=${shared.state.value.isPlaying}, " +
                            "buffering=${shared.state.value.buffering}, avdiff=${property.invoke(shared.player, 10005, Float.NaN)}, " +
                            "audioCacheMs=${shared.player.audioCachedDuration}, videoCacheMs=${shared.player.videoCachedDuration}, " +
                            "audioPackets=${shared.player.audioCachedPackets}, videoPackets=${shared.player.videoCachedPackets}"
                    })
                    nextSample = SystemClock.elapsedRealtime() + 2_000
                }
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertTrue(message, callOnMain(condition))
        }
        try {
            runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.HARDWARE) }
            awaitCondition("hardware preference must load") { shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                attachSurface(scenario, shared)
                if (configuredSource) {
                    lateinit var viewModel: com.vibeplayer.app.ui.webdav.WebDavPlayerViewModel
                    scenario.onActivity { viewModel = ViewModelProvider(it)[com.vibeplayer.app.ui.webdav.WebDavPlayerViewModel::class.java] }
                    // Use this app's existing injected dependencies; never read another app's
                    // storage or serialize credentials. Reflection is confined to this opt-in probe.
                    fun dependency(name: String): Any = viewModel.javaClass.getDeclaredField(name)
                        .apply { isAccessible = true }.get(viewModel).let(::requireNotNull)
                    val repository = dependency("repository") as com.vibeplayer.app.data.repository.MediaServerRepository
                    val encryptedHls = dependency("encryptedHlsManager") as com.vibeplayer.app.player.hls.EncryptedHlsManager
                    val server = runBlocking {
                        repository.observeVisibleServices(app.privacyManager.privacyMode.value).first()
                            .single { it.name == serviceName && it.serviceType == com.vibeplayer.app.model.ServiceType.WEBDAV }
                    }
                    configuredPlayback = runBlocking { encryptedHls.prepareWebDav(server, requireNotNull(path)).getOrThrow() }
                }
                callOnMain {
                    // The original app owns the WebDAV reads. This probe exercises the fixed
                    // native render gate only when a port, rather than a configured source, is used.
                    val playback = configuredPlayback
                    shared.play(playback?.playUrl ?: "http://127.0.0.1:$port/index.m3u8", "Original TSSL seek probe", null,
                        networkByteCounter = playback?.networkByteCounter,
                        interruptPendingReads = if (playback != null) playback::interruptPendingReads else ({}))
                }
                awaitOriginal("original audio/video must start") {
                    shared.state.value.isPlaying && shared.state.value.positionMs > 500 &&
                        shared.state.value.audioTracks.isNotEmpty()
                }
                report.appendText(callOnMain {
                    "durationMs=${shared.state.value.durationMs}, width=${shared.player.videoWidth}, height=${shared.player.videoHeight}\n"
                })
                for (target in listOf(24_000L, 90_000L, 150_000L, 30_000L)) {
                    val started = SystemClock.elapsedRealtime()
                    callOnMain { shared.seekTo(target) }
                    awaitOriginal("original seek must restore both outputs at $target") {
                        shared.state.value.isPlaying && !shared.state.value.buffering &&
                            shared.player.currentPosition in (target - 1_000)..(target + 8_000)
                    }
                    val recoveryMs = SystemClock.elapsedRealtime() - started
                    SystemClock.sleep(500)
                    val settleDeadline = SystemClock.elapsedRealtime() + 1_000
                    while (SystemClock.elapsedRealtime() < settleDeadline) {
                        val difference = callOnMain { property.invoke(shared.player, 10005, Float.NaN) as Float }
                        if (difference.isFinite() && kotlin.math.abs(difference) <= .25f) break
                        SystemClock.sleep(50)
                    }
                    val differences = (1..30).map {
                        val difference = callOnMain { property.invoke(shared.player, 10005, Float.NaN) as Float }
                        SystemClock.sleep(100)
                        difference
                    }
                    report.appendText("targetMs=$target recoveryMs=$recoveryMs A-V=$differences\n")
                    assertTrue("original seek must keep A/V within 250ms: $differences",
                        differences.all { it.isFinite() && kotlin.math.abs(it) <= .25f })
                }
            }
        } finally {
            callOnMain {
                shared.player.setOnInfoListener(originalInfo)
                shared.stop()
                surfaceObserver?.cancel()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            configuredPlayback?.close()
            runBlocking { app.settingsDataStore.setVideoDecodeMode(originalMode) }
        }
    }

    private fun assertBlueFrameVisible(view: IjkPlayerView) {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        callOnMain {
            PixelCopy.request((view.getChildAt(0) as SurfaceView).holder.surface, bitmap, { status ->
                result = status
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        assertTrue(copied.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        try {
            assertEquals(PixelCopy.SUCCESS, result)
            var blue = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.blue(pixel) > Color.red(pixel) + 80 && Color.blue(pixel) > Color.green(pixel) + 80) blue++
            }
            assertTrue("seek must display the blue 20–30s segment, rather than old red/green frames",
                blue > 30)
        } finally { bitmap.recycle() }
    }

    @Test
    fun pausedSurfaceReplacementRedrawsFrameAndRetainsSubtitleSessionInBothDecoders() {
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val original = runBlocking { app.settingsDataStore.playerPreferences.first().videoDecodeMode }
        val sample = copyResource(R.raw.video_sample_controls, ".mp4")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                VideoDecodeMode.entries.forEach { mode ->
                    runBlocking { app.settingsDataStore.setVideoDecodeMode(mode) }
                    awaitCondition("$mode preference must be loaded") { shared.preferences.value.videoDecodeMode == mode }
                    var surface = attachSurface(scenario, shared)
                    callOnMain { shared.play(sample.absolutePath, "Paused surface $mode", null) }
                    awaitCondition("$mode video must render before pausing") {
                        assertNull(shared.state.value.error)
                        shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS &&
                            shared.player.videoOutputFramesPerSecond > 0f
                    }
                    assertColourBarsVisible(surface)
                    callOnMain { shared.pause(); shared.seekTo(0) }
                    awaitCondition("$mode paused seek must reach the beginning") {
                        !shared.state.value.isPlaying && shared.state.value.positionMs < 300
                    }
                    val session = callOnMain { shared.subtitleSessionId }
                    val audio = shared.state.value.selectedAudioTrackKey
                    val generation = shared.state.value.surfaceGeneration
                    callOnMain {
                        val document = com.vibeplayer.app.domain.subtitle.TextSubtitles.parse(
                            "1\n00:00:00,000 --> 00:00:29,000\nRetained subtitle".toByteArray(), "retained.srt")
                        shared.addManualSubtitle(session, document)
                    }
                    val subtitle = shared.state.value.selectedSubtitleKey
                    for (target in listOf(0L, 15_000L)) {
                        callOnMain { shared.seekTo(target) }
                        awaitCondition("$mode paused seek must reach $target") {
                            kotlin.math.abs(shared.state.value.positionMs - target) < 300
                        }
                        // Replacing the view destroys its real Surface and attaches a new one,
                        // reproducing a full-screen document picker without resuming playback.
                        surface = attachSurface(scenario, shared)
                        awaitColourBarsVisible(surface)
                        awaitCondition("$mode surface refresh must finish paused") { !shared.player.isPlaying }
                        callOnMain {
                            assertNull(shared.state.value.error)
                            assertTrue(shared.state.value.isPrepared)
                            assertFalse("surface restoration must keep the UI paused", shared.state.value.isPlaying)
                            assertTrue("surface restoration must not advance playback",
                                kotlin.math.abs(shared.state.value.positionMs - target) < 300)
                            assertEquals(session, shared.subtitleSessionId)
                            assertEquals("restoration must not trigger another Surface rebuild", generation, shared.state.value.surfaceGeneration)
                            assertEquals(audio, shared.state.value.selectedAudioTrackKey)
                            assertEquals(subtitle, shared.state.value.selectedSubtitleKey)
                            assertEquals("Retained subtitle", shared.state.value.subtitleText)
                        }
                    }
                    // A system document picker stops/resumes the SAME Activity and
                    // SurfaceView. This differs from replacing the entire view above:
                    // Android reuses the Java Surface identity with a new native buffer.
                    for (target in listOf(0L, 15_000L)) {
                        callOnMain { shared.seekTo(target) }
                        awaitCondition("$mode lifecycle seek must reach $target") {
                            kotlin.math.abs(shared.state.value.positionMs - target) < 300
                        }
                        val beforeGeneration = shared.state.value.surfaceGeneration
                        scenario.moveToState(Lifecycle.State.CREATED)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        awaitCondition("$mode reused Surface must become valid") {
                            shared.state.value.isPrepared &&
                                (surface.getChildAt(0) as SurfaceView).holder.surface.isValid
                        }
                        awaitColourBarsVisible(surface)
                        awaitCondition("$mode picker lifecycle must stay paused") { !shared.player.isPlaying }
                        callOnMain {
                            assertFalse(shared.state.value.isPlaying)
                            assertEquals("codec recovery must not rebuild the Surface again", beforeGeneration, shared.state.value.surfaceGeneration)
                            assertTrue("$mode lifecycle position ${shared.state.value.positionMs} must retain $target",
                                kotlin.math.abs(shared.state.value.positionMs - target) < 300)
                            assertTrue("$mode native position must also retain $target (actual ${shared.player.currentPosition})",
                                kotlin.math.abs(shared.player.currentPosition - target) < 300)
                            assertEquals(session, shared.subtitleSessionId)
                            assertEquals(audio, shared.state.value.selectedAudioTrackKey)
                            assertEquals(subtitle, shared.state.value.selectedSubtitleKey)
                            assertEquals("Retained subtitle", shared.state.value.subtitleText)
                        }
                    }
                    // A file-picker result may arrive immediately after Surface creation.
                    // It must remain importable in this same prepared session.
                    surface = attachSurface(scenario, shared)
                    callOnMain {
                        shared.ensureSubtitleSession(session)
                        val document = com.vibeplayer.app.domain.subtitle.TextSubtitles.parse(
                            "1\n00:00:00,000 --> 00:00:29,000\nPicker result".toByteArray(), "picker.srt")
                        shared.addManualSubtitle(session, document)
                        assertEquals("Picker result", shared.state.value.subtitleText)
                    }
                    awaitColourBarsVisible(surface)
                    awaitCondition("$mode picker return must remain paused") { !shared.player.isPlaying }
                    callOnMain {
                        assertFalse(shared.state.value.isPlaying)
                        assertEquals(session, shared.subtitleSessionId)
                        assertEquals("Picker result", shared.state.value.subtitleText)
                        shared.stop()
                        shared.beginLoading()
                    }
                }
            }
        } finally {
            callOnMain {
                shared.stop()
                shared.beginLoading()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(original) }
            sample.delete()
        }
    }

    @Test
    fun diagnosticsMeasureRenderedFramesAndThrottledNetworkAndResetAfterStop() {
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val app = context.applicationContext as VibePlayerApp
        val shared = app.playerManager
        val original = runBlocking { app.settingsDataStore.playerPreferences.first().metrics }
        val sample = copyResource(R.raw.video_sample_controls, ".mp4")
        val bytes = sample.readBytes()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath != "/diagnostics.mp4") return MockResponse().setResponseCode(404)
                    val range = request.getHeader("Range")?.removePrefix("bytes=")
                    val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
                    val end = range?.substringAfter('-', "")?.toIntOrNull()?.coerceAtMost(bytes.lastIndex)
                        ?: bytes.lastIndex
                    if (start !in bytes.indices || end < start) return MockResponse().setResponseCode(416)
                    val response = MockResponse().setHeader("Accept-Ranges", "bytes").setHeader("Content-Type", "video/mp4")
                    if (range != null) response.setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    // This 30-second fixture has its moov atom at the end, so range requests
                    // must work. Transfer its 423 KiB over about 13 seconds, above its bitrate
                    // but across several diagnostic samples rather than one fast prefetch.
                    return response.setBody(Buffer().write(bytes, start, end - start + 1))
                        .throttleBody(8 * 1024L, 250, TimeUnit.MILLISECONDS)
                }
            }
            start(InetAddress.getByName("0.0.0.0"), 0)
        }
        try {
            runBlocking {
                app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.FRAME_RATE, true)
                app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.NETWORK_SPEED, true)
            }
            awaitCondition("both diagnostic preferences must be loaded") {
                shared.preferences.value.metrics.frameRate && shared.preferences.value.metrics.networkSpeed
            }
            val remote = server.url("/diagnostics.mp4").newBuilder().host("127.0.0.2").build().toString()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val surface = attachSurface(scenario, shared)
                callOnMain { shared.play(sample.absolutePath, "Local diagnostics", null) }
                awaitCondition("local playback must report rendered FPS without network traffic") {
                    assertNull(shared.state.value.error)
                    assertNull("local playback must not invent network traffic", shared.diagnostics.value.networkBytesPerSecond)
                    shared.state.value.isPlaying && (shared.diagnostics.value.outputFps ?: 0f) > 0f
                }
                assertColourBarsVisible(surface)
                callOnMain { shared.pause() }
                awaitCondition("pausing local playback must sample zero output FPS") {
                    !shared.state.value.isPlaying && shared.diagnostics.value.outputFps == 0f
                }
                callOnMain {
                    shared.stop()
                    assertEquals(PlaybackDiagnostics(), shared.diagnostics.value)
                    shared.play(remote, "Network diagnostics", null)
                }
                var positiveSince: Long? = null
                awaitCondition("throttled playback must report FPS and positive network speed across sampling windows") {
                    assertNull(shared.state.value.error)
                    val diagnostics = shared.diagnostics.value
                    val positive = shared.state.value.isPlaying && (diagnostics.outputFps ?: 0f) > 0f &&
                        (diagnostics.networkBytesPerSecond ?: 0) > 0
                    val now = SystemClock.elapsedRealtime()
                    if (!positive) positiveSince = null
                    else if (positiveSince == null) positiveSince = now
                    positive && now - (positiveSince ?: now) >= 2_100L
                }
                assertColourBarsVisible(surface)
                runBlocking { app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.FRAME_RATE, false) }
                awaitCondition("network-only sampling must continue without FPS") {
                    !shared.preferences.value.metrics.frameRate && shared.diagnostics.value.outputFps == null &&
                        shared.diagnostics.value.networkBytesPerSecond != null
                }
                runBlocking {
                    app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.FRAME_RATE, true)
                    app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.NETWORK_SPEED, false)
                }
                awaitCondition("FPS-only sampling must continue without network readings") {
                    !shared.preferences.value.metrics.networkSpeed && shared.diagnostics.value.networkBytesPerSecond == null &&
                        (shared.diagnostics.value.outputFps ?: 0f) > 0f
                }
                runBlocking { app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.NETWORK_SPEED, true) }
                callOnMain { shared.pause() }
                awaitCondition("pausing remote playback must sample zero output FPS") {
                    !shared.state.value.isPlaying && shared.diagnostics.value.outputFps == 0f
                }
                // A paused player may continue to buffer; NET should reflect those real bytes.
                callOnMain {
                    shared.stop()
                    assertEquals("stopping must discard both diagnostic values", PlaybackDiagnostics(), shared.diagnostics.value)
                }
                assertTrue("network diagnostics must come from an actual HTTP response", server.requestCount > 0)
            }
        } finally {
            callOnMain {
                shared.stop()
                shared.beginLoading()
                context.stopService(Intent(context, PlaybackService::class.java))
            }
            runBlocking {
                app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.FRAME_RATE, original.frameRate)
                app.settingsDataStore.setPlayerMetricVisible(PlayerMetric.NETWORK_SPEED, original.networkSpeed)
            }
            server.shutdown()
            sample.delete()
        }
    }

    @Test
    fun decoderSwitchRetainsPositionPauseAndTracksAndRendersInSoftware() {
        val app = instrumentation.targetContext.applicationContext as VibePlayerApp
        val original = app.playerManager.preferences.value.videoDecodeMode
        // A 30-second remux leaves room for both decoder reloads and Surface copies on slow devices.
        val sample = copyResource(R.raw.video_sample_controls, ".mp4")
        val shared = app.playerManager
        try {
            runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.HARDWARE) }
            awaitCondition("hardware preference must be loaded") { shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val surface = attachSurface(scenario, shared)
                callOnMain { shared.play(sample.absolutePath, "Decoder switch", null) }
                awaitCondition("initial video must play") { shared.state.value.isPlaying && shared.state.value.positionMs >= MIN_POSITION_MS }
                val originalDecoder = callOnMain { shared.player.videoDecoder }
                val audio = shared.state.value.selectedAudioTrackKey
                callOnMain { shared.pause(); shared.seekTo(1_200) }
                awaitCondition("paused seek position must be retained") { shared.state.value.positionMs >= 1_000 }
                val position = shared.state.value.positionMs
                val subtitleSession = callOnMain { shared.subtitleSessionId }
                callOnMain {
                    val document = com.vibeplayer.app.domain.subtitle.TextSubtitles.parse(
                        "1\n00:00:00,000 --> 00:00:29,000\nPhone subtitle".toByteArray(), "phone.srt")
                    shared.addManualSubtitle(subtitleSession, document)
                    assertEquals("Phone subtitle", shared.state.value.subtitleText)
                }
                val subtitleKey = shared.state.value.selectedSubtitleKey
                runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.SOFTWARE) }
                awaitCondition("software reload must retain the paused position") {
                    assertNull(shared.state.value.error)
                    shared.state.value.isPrepared && shared.player.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC &&
                        kotlin.math.abs(shared.state.value.positionMs - position) < 300 &&
                        shared.state.value.selectedSubtitleKey == subtitleKey
                }
                callOnMain {
                    assertFalse("switching must keep a paused video paused", shared.state.value.isPlaying)
                    assertEquals(audio, shared.state.value.selectedAudioTrackKey)
                    assertEquals("Phone subtitle", shared.state.value.subtitleText)
                    shared.selectSubtitle(null, persist = false)
                    assertNull(shared.state.value.subtitleText)
                    shared.selectSubtitle(shared.state.value.subtitleTracks.first { it.key == subtitleKey }, persist = false)
                    shared.play()
                }
                awaitCondition("software decoder must render and advance after the paused seek") {
                    shared.player.videoOutputFramesPerSecond > 0f && shared.state.value.isPlaying &&
                        shared.state.value.positionMs > position + 300
                }
                assertColourBarsVisible(surface)
                val softwarePosition = shared.state.value.positionMs
                runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.HARDWARE) }
                try {
                    awaitCondition("hardware preference must restart and resume playback") {
                        assertNull(shared.state.value.error)
                        shared.preferences.value.videoDecodeMode == VideoDecodeMode.HARDWARE &&
                            shared.state.value.isPlaying && shared.player.videoDecoder == originalDecoder &&
                            shared.player.videoOutputFramesPerSecond > 0f &&
                            shared.state.value.positionMs > softwarePosition + 300
                    }
                } catch (error: AssertionError) {
                    throw AssertionError("decoder switch: original=$originalDecoder actual=${shared.player.videoDecoder} " +
                        "mode=${shared.preferences.value.videoDecodeMode} prepared=${shared.state.value.isPrepared} " +
                        "playing=${shared.state.value.isPlaying} buffering=${shared.state.value.buffering} " +
                        "position=${shared.state.value.positionMs}/${shared.state.value.durationMs}", error)
                }
                assertColourBarsVisible(surface)
                callOnMain {
                    assertEquals(subtitleKey, shared.state.value.selectedSubtitleKey)
                    shared.stop()
                    assertTrue(shared.state.value.subtitleTracks.none { it.key.startsWith("manual:") })
                    try {
                        shared.ensureSubtitleSession(subtitleSession)
                        throw AssertionError("An import from an old session must be rejected")
                    } catch (error: com.vibeplayer.app.domain.subtitle.SubtitleImportException) {
                        assertEquals(com.vibeplayer.app.domain.subtitle.SubtitleImportError.SESSION_CHANGED, error.reason)
                    }
                }
            }
        } finally {
            callOnMain {
                shared.stop()
                shared.beginLoading()
                instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, PlaybackService::class.java))
            }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(original) }
            sample.delete()
        }
    }

    @Test
    fun httpHlsRendersThroughProxyOutsideLocalhostAllowlist() {
        val segment = instrumentation.context.resources.openRawResource(R.raw.video_sample_hls).use { it.readBytes() }
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/iptv/master.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nchannel.m3u8\n"
                    )
                    "/iptv/channel.m3u8" -> MockResponse().setBody(
                        "#EXTM3U\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.0,\nchunk.ts\n#EXT-X-ENDLIST\n"
                    )
                    "/iptv/chunk.ts" -> MockResponse().setBody(Buffer().write(segment))
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start(InetAddress.getByName("0.0.0.0"), 0)
        }
        var manager: PlayerManager? = null
        try {
            val url = server.url("/iptv/master.m3u8").newBuilder().host("127.0.0.2").build().toString()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { manager = (it.application as VibePlayerApp).playerManager }
                    val shared = checkNotNull(manager)
                    val surface = attachSurface(scenario, shared)
                    // A second play creates another loopback port. IJK's global DNS cache must
                    // not connect to the closed proxy from the preceding HLS session.
                    repeat(2) { round ->
                        callOnMain { shared.play(url, "IPTV HTTP regression $round", null) }
                        awaitCondition("HTTP HLS must prepare, render and advance in round $round") {
                            val state = shared.state.value
                            assertNull("HTTP HLS round $round playback error", state.error)
                            state.isPrepared && state.isPlaying && state.positionMs >= MIN_POSITION_MS &&
                                shared.player.videoOutputFramesPerSecond > 0f
                        }
                        assertColourBarsVisible(surface)
                        callOnMain { shared.stop() }
                    }
                    assertTrue("both rounds must load the master, child and segment", server.requestCount >= 6)
                } finally {
                    callOnMain {
                        manager?.stop()
                        manager?.beginLoading()
                        instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, PlaybackService::class.java))
                    }
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun videoRendersAfterSourceFailureAndRepeatedResetStopAndSurfaceReplacement() {
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Keep MainActivity's notification permission dialog from obscuring the video.
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val samples = listOf(
            Sample(copyResource(R.raw.video_sample_ac3, ".mp4"), "ac3"),
            Sample(copyResource(R.raw.video_sample_dts, ".mkv"), "dts")
        )
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val sample = samples.firstOrNull { request.path?.substringBefore('?') == "/${it.codec}[sample]" }
                        ?: return MockResponse().setResponseCode(404)
                    val bytes = sample.file.readBytes()
                    val range = request.getHeader("Range")
                    val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (start !in bytes.indices) return MockResponse().setResponseCode(416)
                    val response = MockResponse().setHeader("Accept-Ranges", "bytes")
                    if (range != null) {
                        response.setResponseCode(206)
                            .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    }
                    return response.setBody(Buffer().write(bytes, start, bytes.size - start))
                }
            }
            // Use an address outside the old localhost-only cleartext allowlist.
            // Otherwise the test passes even when every real HTTP IPTV/DAV source is blocked.
            start(InetAddress.getByName("0.0.0.0"), 0)
        }
        var manager: PlayerManager? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { activity ->
                        // Reuse the instance also injected into PlaybackService; constructing another
                        // SettingsDataStore would contend for the application's preferences file.
                        manager = (activity.application as VibePlayerApp).playerManager
                    }
                    val shared = checkNotNull(manager)
                    var surface = attachSurface(scenario, shared)

                    val missing = File(context.cacheDir, "missing-${SystemClock.elapsedRealtimeNanos()}.mp4")
                    callOnMain { shared.play(missing.absolutePath, "Missing sample", null) }
                    awaitCondition("unreadable source must become a playback error") {
                        shared.state.value.error != null && !shared.state.value.buffering
                    }

                    repeat(2) { round ->
                        samples.forEach { sample ->
                            // MockWebServer.url() may resolve the local hostname; keep it off main.
                            val url = if (round == 0) sample.file.absolutePath else
                                server.url("/${sample.codec}[sample]").newBuilder().host("127.0.0.2").build().toString()
                            callOnMain {
                                shared.beginLoading(title = "${sample.codec} round $round")
                                // The remote address goes through PlaybackHttpProxy and IJK's HTTP/TCP
                                // context options. File-only tests miss tagged-pointer corruption.
                                shared.play(url, sample.codec, null)
                            }
                            awaitCondition("${sample.codec} must prepare, render and advance after reset") {
                                val state = shared.state.value
                                assertNull("${sample.codec} round $round playback error", state.error)
                                state.isPrepared && state.isPlaying && state.positionMs >= MIN_POSITION_MS &&
                                    shared.player.videoOutputFramesPerSecond > 0f
                            }
                            callOnMain {
                                assertTrue("video surface must remain valid", (surface.getChildAt(0) as SurfaceView).holder.surface.isValid)
                                assertTrue("video dimensions must be available", shared.player.videoWidth > 0 && shared.player.videoHeight > 0)
                                // The app requests MediaCodec, but IJK may legitimately fall back on
                                // a device without a usable AVC decoder. Both paths must render.
                                val decoder = shared.player.videoDecoder
                                assertTrue(
                                    "a video decoder must be selected (actual $decoder)",
                                    decoder == IjkMediaPlayer.FFP_PROPV_DECODER_MEDIACODEC ||
                                        decoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC
                                )
                                val audio = shared.player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)
                                assertEquals(sample.codec, shared.player.trackInfo?.getOrNull(audio)?.format
                                    ?.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME))
                                assertEquals("avcodec", shared.player.mediaInfo?.mAudioDecoder)
                            }
                            assertColourBarsVisible(surface)
                            callOnMain {
                                shared.stop()
                                assertFalse("stop must clear preparation", shared.state.value.isPrepared)
                                assertFalse("stop must clear playing state", shared.state.value.isPlaying)
                            }
                            // A new view destroys the old Surface and binds a fresh one to the same
                            // player, just as leaving and reopening a native player page does.
                            surface = attachSurface(scenario, shared)
                        }
                    }
                    assertTrue("network round must reach the HTTP origin", server.requestCount >= samples.size)
                } finally {
                    callOnMain {
                        manager?.stop()
                        manager?.beginLoading()
                        context.stopService(Intent(context, PlaybackService::class.java))
                    }
                }
            }
        } finally {
            server.shutdown()
            samples.forEach { it.file.delete() }
        }
    }

    private data class Sample(val file: File, val codec: String)

    private fun attachSurface(scenario: ActivityScenario<MainActivity>, manager: PlayerManager): IjkPlayerView {
        val created = CountDownLatch(1)
        lateinit var view: IjkPlayerView
        scenario.onActivity { activity ->
            surfaceObserver?.cancel()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view = IjkPlayerView(activity, { holder, attached ->
                manager.setVideoSurface(holder, attached)
                if (attached) created.countDown()
            }).apply { player = manager.player }
            activity.setContentView(view)
            surfaceObserver = activity.lifecycleScope.launch {
                manager.state.map { it.surfaceGeneration to it.videoOutputBlocked }.distinctUntilChanged().collect { (generation, blocked) ->
                    view.updateSurfaceGeneration(generation)
                    view.videoOutputBlocked = blocked
                }
            }
        }
        assertTrue("video surface must be created", created.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return view
    }

    /** A newly attached, paused Surface may briefly have no buffer before its refresh finishes. */
    private fun awaitColourBarsVisible(view: IjkPlayerView) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        var lastFailure: AssertionError? = null
        do {
            try {
                assertColourBarsVisible(view)
                return
            } catch (failure: AssertionError) {
                lastFailure = failure
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("a paused replacement Surface must receive a colour-bar frame", lastFailure)
    }

    /** Native output statistics alone cannot establish that pixels reached the Surface. */
    private fun assertColourBarsVisible(view: IjkPlayerView) {
        val bitmap = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            callOnMain {
                val surface = view.getChildAt(0) as SurfaceView
                PixelCopy.request(surface, bitmap, { status ->
                    result = status
                    copied.countDown()
                }, Handler(Looper.getMainLooper()))
            }
            assertTrue("video Surface copy must finish", copied.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals("video Surface must contain a rendered buffer", PixelCopy.SUCCESS, result)
            var colouredPixels = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    val brightest = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    val darkest = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    if (brightest - darkest > 80) colouredPixels++
                }
            }
            assertTrue("colour bars must be visible, rather than a blank Surface", colouredPixels > 50)
        } finally {
            // PixelCopy owns the destination until its callback, including on slow devices.
            if (copied.count == 0L) bitmap.recycle()
        }
    }

    private fun copyResource(resource: Int, suffix: String): File {
        val file = File.createTempFile("ijk-video-", suffix, instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.resources.openRawResource(resource).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        do {
            if (callOnMain(condition)) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue(message, callOnMain(condition))
    }

    private fun <T> callOnMain(body: () -> T): T {
        val box = arrayOfNulls<Any>(2)
        instrumentation.runOnMainSync {
            try {
                box[0] = body()
            } catch (failure: Throwable) {
                box[1] = failure
            }
        }
        (box[1] as Throwable?)?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val MIN_POSITION_MS = 500L
    }
}
