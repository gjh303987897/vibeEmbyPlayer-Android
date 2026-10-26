package com.vibeplayer.app.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.service.PlaybackService
import com.vibeplayer.app.ui.player.IjkPlayerView
import com.vibeplayer.app.ui.player.PlayerViewModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/** Explicit opt-in only. Existing credentials stay within their own app and are never logged. */
@RunWith(AndroidJUnit4::class)
class ConfiguredEmbySpeedDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as VibePlayerApp
    private val manager get() = app.playerManager
    private val property = IjkMediaPlayer::class.java.getDeclaredMethod("_getPropertyFloat",
        Int::class.javaPrimitiveType, Float::class.javaPrimitiveType).apply { isAccessible = true }

    @Test fun configuredEpisodeFollowsPresentedAudioAtSpeed() {
        val args = InstrumentationRegistry.getArguments()
        val service = args.getString("configuredEmbyService")
        val title = args.getString("configuredEmbySeries")
        assumeTrue("requires explicitly selected service and series", !service.isNullOrBlank() && !title.isNullOrBlank())
        val requestedTitle = requireNotNull(title)
        assertEquals("device verification must remain muted", 0,
            (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(AudioManager.STREAM_MUSIC))
        val original = runBlocking { app.settingsDataStore.playerPreferences.first() }
        val report = File(app.getExternalFilesDir(null), "configured-emby-speed.txt")
        report.writeText("Opt-in configured Emby episode; muted presentation-clock verification, no acoustic recording.\n")
        val failures = mutableListOf<String>()
        var observer: Job? = null
        fun <T> checked(result: Result<T>, operation: String): T = result.getOrNull()
            ?: error("$operation failed (${result.exceptionOrNull()?.javaClass?.simpleName})")
        try {
            main { manager.stop(); manager.setPlaybackSpeed(1f) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var owner: PlayerViewModel
                lateinit var view: IjkPlayerView
                val created = CountDownLatch(1)
                scenario.onActivity { activity ->
                    owner = ViewModelProvider(activity)[PlayerViewModel::class.java]
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
                assertTrue("video Surface", created.await(10, TimeUnit.SECONDS))
                val repository = PlayerViewModel::class.java.getDeclaredField("repository")
                    .apply { isAccessible = true }.get(owner) as MediaServerRepository
                val server = runBlocking {
                    repository.observeVisibleServices(app.privacyManager.privacyMode.value).first()
                        .single { it.name == service }
                }
                check(!server.privateMode || app.privacyManager.privacyMode.value) { "Selected service is locked" }
                val session = repository.restoreSession(server) ?: error("Selected service needs sign-in in this app")
                val client = repository.clientFor(server.serviceType)
                val episode = runBlocking {
                    val candidates = checked(client.searchItems(session, requestedTitle, 0, 50), "Series lookup").items
                        .filter { it.itemType == "Series" && it.name.contains(requestedTitle) }
                    val series = candidates.firstOrNull { it.name == requestedTitle } ?: candidates.single()
                    report.appendText("selectedSeries=${series.name}\n")
                    val season = checked(client.fetchSeriesSeasons(session, series.id), "Season lookup").first()
                    checked(client.fetchSeasonEpisodes(session, series.id, season.id), "Episode lookup").first()
                }
                val target = runBlocking { checked(client.fetchPlaybackUrl(session, episode), "Playback lookup") }
                val modes = when (args.getString("configuredEmbyDecoder")) {
                    "hardware" -> listOf(VideoDecodeMode.HARDWARE)
                    "software" -> listOf(VideoDecodeMode.SOFTWARE)
                    else -> listOf(VideoDecodeMode.HARDWARE, VideoDecodeMode.SOFTWARE)
                }
                for (mode in modes) {
                    main { manager.stop(); manager.setPlaybackSpeed(1f) }
                    runBlocking { app.settingsDataStore.setVideoDecodeMode(mode) }
                    await("decoder preference") { manager.preferences.value.videoDecodeMode == mode }
                    main {
                        manager.play(target.url, episode.name, null, startPositionMs = 30_000,
                            headers = mapOf("X-Emby-Token" to session.accessToken),
                            trustSelfSignedCertificate = server.trustSelfSignedCertificate,
                            allowInsecureHttp = server.allowInsecureHttp, privatePlayback = server.privateMode)
                        manager.player.setVolume(0f, 0f)
                    }
                    await("episode output") { manager.state.value.isPrepared && manager.state.value.isPlaying &&
                        manager.player.currentPosition > 30_300 && manager.player.videoOutputFramesPerSecond > 0f }
                    report.appendText(main {
                        "requestedMode=$mode actualMode=${manager.state.value.videoDecodeMode} " +
                            "videoDecoder=${manager.player.mediaInfo?.mVideoDecoder} " +
                            "audioDecoder=${manager.player.mediaInfo?.mAudioDecoder} " +
                            "videoMetadata=${manager.state.value.mediaInfo.video} " +
                            "audioMetadata=${manager.state.value.mediaInfo.audio}\n"
                    })
                    val speeds = args.getString("configuredEmbySpeeds")?.split(',')?.mapNotNull { it.toFloatOrNull() }
                        ?.takeIf { it.isNotEmpty() && it.all { rate -> rate.isFinite() && rate in .25f..4f } }
                        ?: listOf(1f, 1.5f, 2f, 1f)
                    for (speed in speeds) {
                        main { manager.setPlaybackSpeed(speed); manager.player.setVolume(0f, 0f) }
                        if (args.getString("expectAdaptiveSpeed") == "true" && speed == 2f) {
                            await("decoder pressure reduces speed with a notice") {
                                manager.state.value.speedAdjustment?.let { it.requested == speed && it.actual < speed } == true
                            }
                            var stableSince = 0L
                            var stableSpeed = 0f
                            await("outputs recover after adaptive seek") {
                                val state = manager.state.value
                                val now = SystemClock.elapsedRealtime()
                                val valid = !state.buffering && state.isPlaying &&
                                    abs(metric(10005)) / state.playbackSpeed < avTolerance(state.playbackSpeed)
                                if (!valid || state.playbackSpeed != stableSpeed) {
                                    stableSince = if (valid) now else 0L
                                    stableSpeed = state.playbackSpeed
                                } else if (stableSince == 0L) stableSince = now
                                valid && now - stableSince >= 3_000
                            }
                        }
                        SystemClock.sleep(1_500)
                        val effective = main { manager.state.value.playbackSpeed }
                        var used = 0
                        var peak = 0f
                        var finite = true
                        val differences = mutableListOf<Float>()
                        val count = if (speed == 2f) 72 else 36
                        val begin = main { manager.player.currentPosition }
                        val wall = SystemClock.elapsedRealtime()
                        repeat(count) {
                            main {
                                assertNull(manager.state.value.error)
                                if (metric(10901) == 1f) ++used
                                val difference = metric(10005)
                                if (difference.isFinite()) peak = maxOf(peak, abs(difference) / effective)
                                else finite = false
                                differences += difference / effective
                                report.appendText("sample speed=$speed wall=${SystemClock.elapsedRealtime() - wall} " +
                                    "position=${manager.player.currentPosition} signedAvMedia=${metric(10005)} " +
                                    "presentation=${metric(10901)} backlogMedia=${metric(10902)} " +
                                    "decodeFps=${metric(10001)} outputFps=${metric(10002)} wait=${metric(10004)}\n")
                            }
                            SystemClock.sleep(150)
                        }
                        val actual = (main { manager.player.currentPosition } - begin).toDouble() / (SystemClock.elapsedRealtime() - wall)
                        val tolerance = main { avTolerance(effective) }
                        val firstDifference = differences.take(count / 3).sorted()[count / 6]
                        val lastDifference = differences.takeLast(count / 3).sorted()[count / 6]
                        val drift = abs(lastDifference - firstDifference)
                        val line = "mode=$mode requestedSpeed=$speed effectiveSpeed=$effective actualRate=$actual " +
                            "displayHz=${main { metric(10903) }} outputFps=${main { metric(10002) }} " +
                            "presentationSamples=$used/$count maxAvWallMs=${peak * 1000} " +
                            "avToleranceMs=${tolerance * 1000} driftWallMs=${drift * 1000}\n"
                        report.appendText(line)
                        println(line.trim())
                        if (used < count * .8) failures += "presentation source: $line"
                        if (abs(actual - effective) >= effective * .15) failures += "playback rate: $line"
                        if (main { metric(10002) > metric(10903) * 1.08f }) failures += "display FPS cap: $line"
                        if (!finite || peak >= tolerance || drift >= maxOf(.075f, tolerance - .04f)) failures += "A/V clocks: $line"
                    }
                }
                assertTrue(failures.joinToString("\n"), failures.isEmpty())
            }
        } finally {
            main { observer?.cancel(); manager.stop(); manager.setPlaybackSpeed(1f); app.stopService(Intent(app, PlaybackService::class.java)) }
            runBlocking { app.settingsDataStore.setVideoDecodeMode(original.videoDecodeMode) }
        }
    }

    private fun metric(id: Int) = property.invoke(manager.player, id, Float.NaN) as Float
    // Below 10fps, one source-frame step alone exceeds the usual 150ms window.
    // Keep the normal threshold and allow at most one frame plus 40ms for very slow fallback.
    private fun avTolerance(speed: Float): Float = maxOf(.15f,
        (.04 + 1 / ((manager.state.value.mediaInfo.video?.frameRate ?: 60.0) * speed)).toFloat())
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (main { assertNull(manager.state.value.error); condition() }) return
            SystemClock.sleep(100)
        }
        assertTrue(message, main(condition))
    }
    private fun <T> main(block: () -> T): T {
        val value = arrayOfNulls<Any>(2)
        instrumentation.runOnMainSync { try { value[0] = block() } catch (error: Throwable) { value[1] = error } }
        (value[1] as Throwable?)?.let { throw it }
        @Suppress("UNCHECKED_CAST") return value[0] as T
    }
}
