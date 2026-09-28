package com.vibeplayer.app.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

/**
 * End-to-end proof that the bundled FFmpeg decoder puts sound back into files the phone cannot
 * decode by itself - and that it is the *only* thing doing it.
 *
 * [AudioDecodeSupportTest] pins the decision table and [FfmpegDecoderOnDeviceTest] pins the
 * reflective wiring, but neither shows a real stream going through a real renderer. This test plays
 * the two licensed-format samples in `src/androidTest/res/raw` (AC-3 and DTS; see
 * "测试媒体" in `docs/audio-software-decoding.md` for the commands that generated them) through an
 * `ExoPlayer` configured like [PlayerManager] and asserts:
 *
 * - the audio track is *selected* by a renderer and the playback position advances, i.e. PCM
 *   really comes out of `FfmpegAudioRenderer`;
 * - the same file, played with `EXTENSION_RENDERER_MODE_DISABLED` - the renderer chain this app had
 *   before `decoder_ffmpeg` was added - produces **no selected audio track at all**, which is
 *   exactly the "picture, no sound" failure the feature exists to fix.
 *
 * The two directions together are the causal evidence: without the extension the stream is mute,
 * with it the same stream plays. The AOSP emulator image has no Dolby/DTS licence and therefore
 * reproduces what an unlicensed phone does with a `static=true` direct stream.
 *
 * Each playback runs on its own [HandlerThread]: `ExoPlayer` must be built on a thread with a
 * Looper, that thread then owns the listener callbacks, and the instrumentation thread has to stay
 * free to wait on latches instead of deadlocking the player.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
public class AudioDecodePlaybackTest {

    /** What the player ended up doing with the file's audio track. */
    private data class Outcome(
        val audioMimeType: String?,
        val audioTrackSelected: Boolean,
        val positionAdvancedMs: Long,
        val error: PlaybackException?
    )

    @Test
    @Throws(Exception::class)
    public fun ac3PlaysOnlyBecauseOfTheFfmpegRenderer() {
        val file = sampleToFile(R.raw.decode_sample_ac3, "sample_ac3.mp4")

        val decoded = play(file, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        assertNull("AC-3 playback must not error: ${decoded.error}", decoded.error)
        assertEquals("the playing audio track should be the AC-3 one", MimeTypes.AUDIO_AC3, decoded.audioMimeType)
        assertTrue("AC-3 must be decoded by the FFmpeg renderer", decoded.audioTrackSelected)
        assertAdvancing(decoded)

        val mute = play(file, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        assertNull("control playback must not error either: ${mute.error}", mute.error)
        assertTrue(
            "without the FFmpeg renderer AC-3 has no decoder, so no audio track may be selected " +
                "(that is the silent-video bug this feature fixes)",
            !mute.audioTrackSelected
        )
    }

    @Test
    @Throws(Exception::class)
    public fun dtsPlaysOnlyBecauseOfTheFfmpegRenderer() {
        val file = sampleToFile(R.raw.decode_sample_dts, "sample_dts.mkv")

        val decoded = play(file, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        assertNull("DTS playback must not error: ${decoded.error}", decoded.error)
        assertEquals(MimeTypes.AUDIO_DTS, decoded.audioMimeType)
        assertTrue("DTS must be decoded by the FFmpeg renderer", decoded.audioTrackSelected)
        assertAdvancing(decoded)

        val mute = play(file, DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        assertTrue(
            "without the FFmpeg renderer DTS has no decoder, so no audio track may be selected",
            !mute.audioTrackSelected
        )
    }

    private fun assertAdvancing(outcome: Outcome) {
        assertTrue(
            "the player must move past the start (advanced ${outcome.positionAdvancedMs}ms, expected " +
                ">= ${MIN_ADVANCE_MS}ms) - a track no renderer can decode never gets here",
            outcome.positionAdvancedMs >= MIN_ADVANCE_MS
        )
    }

        /**
     * Copies an androidTest raw resource into the **app's** cache dir so the player reads it as an
     * ordinary local path, like every other direct-play source in the app. The resources live in
     * the test APK, but only the target app owns a writable data directory.
     */
    private fun sampleToFile(resourceId: Int, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = File(instrumentation.targetContext.cacheDir, name)
        target.parentFile?.mkdirs()
        instrumentation.context.resources.openRawResource(resourceId).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("$name is empty", target.length() > 0)
        return target
    }

    /** Plays [file] on a private thread with the given extension-renderer mode and reports the result. */
    private fun play(file: File, extensionRendererMode: Int): Outcome {
        val thread = PlaybackThread()
        var error: PlaybackException? = null
        val readyOrFailed = CountDownLatch(1)
        return try {
            val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
            thread.call {
                val renderersFactory = DefaultRenderersFactory(context)
                    .setEnableDecoderFallback(true)
                    .setExtensionRendererMode(extensionRendererMode)
                val created = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context))
                    .setRenderersFactory(renderersFactory)
                    .build()
                created.addListener(object : Player.Listener {
                    override fun onPlayerError(playbackError: PlaybackException) {
                        error = playbackError
                        readyOrFailed.countDown()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // A selected audio group means some renderer claimed the track; with no
                        // capable renderer Media3 selects nothing and this never fires.
                        if (selectedAudioGroup(tracks) != null) readyOrFailed.countDown()
                    }
                })
                created.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                created.prepare()
                created.playWhenReady = true
                thread.player = created
            }
            readyOrFailed.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val startedAt = SystemClock.elapsedRealtime()
            val first = thread.read { it?.currentPosition ?: -1L }
            while (SystemClock.elapsedRealtime() - startedAt < MEASURE_WINDOW_MS && error == null) {
                Thread.sleep(POLL_INTERVAL_MS)
            }
            val second = thread.read { it?.currentPosition ?: -1L }
            Outcome(
                audioMimeType = thread.read { it?.audioFormat?.sampleMimeType },
                audioTrackSelected = thread.read { selectedAudioGroup(it?.currentTracks ?: EMPTY_TRACKS) != null },
                positionAdvancedMs = second - first,
                error = error
            )
        } finally {
            runCatching { thread.read { it?.release() } }
            thread.close()
        }
    }

    private fun selectedAudioGroup(tracks: Tracks): Tracks.Group? =
        tracks.groups.firstOrNull { group ->
            group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { group.isTrackSelected(it) }
        }

    /** A [HandlerThread] owning an [ExoPlayer]; [call] and [read] run on that thread. */
    private inner class PlaybackThread {
        private val handlerThread = HandlerThread("ffmpeg-playback-test").apply { start() }
        private val handler = Handler(handlerThread.looper)
        var player: ExoPlayer? = null

        fun <T> call(body: () -> T): T {
            val box = arrayOfNulls<Any>(2)
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    box[0] = body() as Any?
                } catch (failure: Throwable) {
                    box[1] = failure
                } finally {
                    latch.countDown()
                }
            }
            assertTrue("playback thread stalled", latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            (box[1] as Throwable?)?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return box[0] as T
        }

        /** Reads from the player thread, falling back to [body] with null so teardown stays quiet. */
        fun <T> read(body: (ExoPlayer?) -> T): T =
            runCatching { call { body(player) } }.getOrElse { body(null) }

        fun close() {
            handlerThread.quitSafely()
        }
    }

    private companion object {
        const val READY_TIMEOUT_MS = 20_000L
        const val MEASURE_WINDOW_MS = 1_500L
        const val POLL_INTERVAL_MS = 100L
        const val MIN_ADVANCE_MS = 300L

        /** Used when the player is gone: "no tracks" is the honest answer. */
        val EMPTY_TRACKS = Tracks(emptyList<Tracks.Group>())
    }
}
