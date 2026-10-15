package com.vibeplayer.app.player

import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.test.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tv.danmaku.ijk.media.player.IjkMediaMeta
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo

/**
 * Plays the bundled AC-3/MP4 and DTS/Matroska samples through the real IJK native player.
 * Preparation and a selected track alone can still describe silent video: both native audio
 * decoding and audio rendering callbacks must arrive, and playback must advance afterward.
 * These regressions require the full FFmpeg build; the stock IJK 0.8.8 codec subset is insufficient.
 */
@RunWith(AndroidJUnit4::class)
public class AudioDecodePlaybackTest {

    private data class Outcome(
        val prepared: Boolean,
        val audioCodec: String?,
        val audioTrackSelected: Boolean,
        val audioDecoder: String?,
        val audioDecoded: Boolean,
        val audioRendered: Boolean,
        val positionAdvancedMs: Long,
        val error: String?
    )

    @Test
    public fun ac3DecodesAndRendersWithIjk() {
        assertSamplePlays(R.raw.decode_sample_ac3, ".mp4", "ac3")
    }

    @Test
    public fun dtsInMatroskaDecodesAndRendersWithIjk() {
        assertSamplePlays(R.raw.decode_sample_dts, ".mkv", "dts")
    }

    private fun assertSamplePlays(resourceId: Int, suffix: String, expectedCodec: String) {
        val file = sampleToFile(resourceId, suffix)
        try {
            val outcome = play(file)
            assertNull("$expectedCodec playback must not error: ${outcome.error}", outcome.error)
            assertTrue("$expectedCodec prepareAsync must succeed within ${READY_TIMEOUT_MS}ms", outcome.prepared)
            assertTrue("$expectedCodec must have a selected audio stream", outcome.audioTrackSelected)
            assertEquals("the selected audio stream must match the sample", expectedCodec, outcome.audioCodec)
            assertEquals("$expectedCodec must use IJK's FFmpeg audio decoder", "avcodec", outcome.audioDecoder)
            assertTrue("$expectedCodec must emit MEDIA_INFO_AUDIO_DECODED_START", outcome.audioDecoded)
            assertTrue("$expectedCodec must emit MEDIA_INFO_AUDIO_RENDERING_START", outcome.audioRendered)
            assertTrue(
                "$expectedCodec position must advance after audio starts: " +
                    "${outcome.positionAdvancedMs}ms, expected >= ${MIN_ADVANCE_MS}ms",
                outcome.positionAdvancedMs >= MIN_ADVANCE_MS
            )
        } finally {
            file.delete()
        }
    }

    /** The samples belong to the test APK; native IJK reads a file in the target app's cache. */
    private fun sampleToFile(resourceId: Int, suffix: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File.createTempFile("ijk-audio-decode-", suffix, instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.resources.openRawResource(resourceId).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return target
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    /** IJK binds callbacks to its constructor's Looper, so all player access uses this thread. */
    private fun play(file: File): Outcome {
        val thread = PlaybackThread()
        val readyOrFailed = CountDownLatch(1)
        var prepared = false
        var decoded = false
        var rendered = false
        var error: String? = null

        fun signalReady() {
            if (prepared && decoded && rendered) readyOrFailed.countDown()
        }

        return try {
            thread.call {
                val player = IjkMediaPlayer()
                thread.player = player
                player.setAudioStreamType(AudioManager.STREAM_MUSIC)
                player.setVolume(1f, 1f)
                player.setOnPreparedListener {
                    prepared = true
                    player.start()
                    signalReady()
                }
                player.setOnInfoListener { _, what, _ ->
                    when (what) {
                        IMediaPlayer.MEDIA_INFO_AUDIO_DECODED_START -> decoded = true
                        IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START -> rendered = true
                    }
                    signalReady()
                    false
                }
                player.setOnErrorListener { _, what, extra ->
                    error = "IJK_ERROR_${what}_${extra}"
                    readyOrFailed.countDown()
                    true
                }
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
            }

            val ready = readyOrFailed.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            var positionAdvanced = 0L
            if (ready && thread.call { error == null && prepared && decoded && rendered }) {
                val firstPosition = thread.call { thread.requirePlayer().currentPosition }
                val startedAt = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - startedAt < MEASURE_WINDOW_MS) {
                    val currentPosition = thread.call {
                        if (error == null) thread.requirePlayer().currentPosition else null
                    } ?: break
                    positionAdvanced = currentPosition - firstPosition
                    if (positionAdvanced >= MIN_ADVANCE_MS) break
                    Thread.sleep(POLL_INTERVAL_MS)
                }
            }

            thread.call {
                val player = thread.requirePlayer()
                val selected = if (prepared && error == null) {
                    val index = player.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO)
                    player.trackInfo?.getOrNull(index)
                } else {
                    null
                }
                Outcome(
                    prepared = prepared,
                    audioCodec = selected?.format?.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME),
                    audioTrackSelected = selected?.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO,
                    audioDecoder = if (prepared && error == null) player.mediaInfo?.mAudioDecoder else null,
                    audioDecoded = decoded,
                    audioRendered = rendered,
                    positionAdvancedMs = positionAdvanced,
                    error = error
                )
            }
        } finally {
            try {
                thread.call { thread.player?.release() }
            } finally {
                thread.close()
            }
        }
    }

    private class PlaybackThread {
        private val handlerThread = HandlerThread("ijk-audio-playback-test").apply { start() }
        private val handler = Handler(handlerThread.looper)
        var player: IjkMediaPlayer? = null

        fun requirePlayer(): IjkMediaPlayer = checkNotNull(player)

        fun <T> call(body: () -> T): T {
            val box = arrayOfNulls<Any>(2)
            val latch = CountDownLatch(1)
            check(handler.post {
                try {
                    box[0] = body()
                } catch (failure: Throwable) {
                    box[1] = failure
                } finally {
                    latch.countDown()
                }
            }) { "playback thread is closed" }
            assertTrue("playback thread stalled", latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            (box[1] as Throwable?)?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return box[0] as T
        }

        fun close() {
            handlerThread.quitSafely()
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val MEASURE_WINDOW_MS = 1_500L
        const val POLL_INTERVAL_MS = 100L
        const val MIN_ADVANCE_MS = 300L
    }
}
