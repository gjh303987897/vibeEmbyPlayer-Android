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
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
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
                runBlocking { app.settingsDataStore.setVideoDecodeMode(VideoDecodeMode.SOFTWARE) }
                awaitCondition("software reload must retain the paused position") {
                    assertNull(shared.state.value.error)
                    shared.state.value.isPrepared && shared.player.videoDecoder == IjkMediaPlayer.FFP_PROPV_DECODER_AVCODEC &&
                        kotlin.math.abs(shared.state.value.positionMs - position) < 300
                }
                callOnMain {
                    assertFalse("switching must keep a paused video paused", shared.state.value.isPlaying)
                    assertEquals(audio, shared.state.value.selectedAudioTrackKey)
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
                manager.state.map { it.surfaceGeneration }.distinctUntilChanged().collect {
                    view.updateSurfaceGeneration(it)
                }
            }
        }
        assertTrue("video surface must be created", created.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        return view
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
