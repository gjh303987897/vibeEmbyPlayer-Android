package com.vibeplayer.app.player

import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that the vendored FFmpeg decoder actually works.
 *
 * The JVM tests in this package pin the *decision logic* and the reflective wiring contract, but
 * they cannot load `libffmpegJNI.so` - only Android's linker can. This test runs on a device (the
 * AOSP emulator image is ideal, as it has no Dolby or DTS licence, which is exactly the case the
 * feature exists for) and asserts the four things that must hold for a silent DTS / TrueHD track
 * to come back with sound:
 *
 * 1. the native library loads and `FfmpegLibrary` reports itself available, i.e. the binary was
 *    packaged for this ABI (see `scripts/build-ffmpeg-decoder.sh`);
 * 2. FFmpeg claims the formats the platform itself cannot decode;
 * 3. `DefaultRenderersFactory` in `EXTENSION_RENDERER_MODE_ON` really instantiates
 *    `FfmpegAudioRenderer` - the reflective lookup upstream depends on, which is invisible to R8;
 * 4. [AudioDecodeSupport] therefore reports [AudioDecodeMode.SOFTWARE], the state the player's
 *    "已启用客户端解码" notice is driven by.
 *
 * `FfmpegLibrary`'s two queries are package-private upstream, hence reflection; the class name is
 * the same one `DefaultRenderersFactory` looks up, so a rename breaks this test and playback
 * together.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
public class FfmpegDecoderOnDeviceTest {

    private val ffmpegLibrary: Class<*> =
        Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary")

    private fun isAvailable(): Boolean =
        ffmpegLibrary.getMethod("isAvailable").invoke(null) as Boolean

    private fun supportsFormat(mimeType: String): Boolean =
        ffmpegLibrary.getMethod("supportsFormat", String::class.java).invoke(null, mimeType) as Boolean

    @Test
    fun ffmpegNativeLibraryIsLoadedOnDevice() {
        assertTrue(
            "libffmpegJNI.so must load on this ABI (${Build.SUPPORTED_ABIS.joinToString()}); " +
                "run scripts/build-ffmpeg-decoder.sh if it is missing",
            isAvailable()
        )
    }

    @Test
    fun ffmpegDecodesWhatThePlatformCannot() {
        assertTrue(isAvailable())
        // Media3's own constants, as they really appear in Format.sampleMimeType:
        // AUDIO_TRUEHD == "audio/true-hd", AUDIO_DTS == "audio/vnd.dts", AUDIO_DTS_HD ==
        // "audio/vnd.dts.hd". ("audio/truehd" is the FFmpeg name, not the Media3 one, and
        // FfmpegLibrary.getCodecName() deliberately returns null for it.)
        // AC-3 / E-AC-3 may be licensed on a given device, so only TrueHD and the DTS family are
        // asserted to be hardware-free; everything is asserted to be FFmpeg-decodable.
        val unlicensable = listOf(
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD
        )
        for (mimeType in unlicensable + listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3)) {
            assertTrue("$mimeType should be software-decodable", supportsFormat(mimeType))
        }
        for (mimeType in unlicensable) {
            assertTrue(
                "$mimeType should not be claimed by MediaCodec on this device",
                !hardwareCanDecode(mimeType)
            )
        }
    }

    @Test
    fun renderersFactoryInstantiatesTheFfmpegAudioRenderer() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderers: Array<Renderer> = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                object : TextOutput {
                    override fun onCues(cueGroup: CueGroup) = Unit
                },
                object : MetadataOutput {
                    override fun onMetadata(metadata: Metadata) = Unit
                }
            )

        val ffmpeg = renderers.firstOrNull {
            it.javaClass.name == "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"
        }
        assertNotNull(
            "Media3 must pick up the vendored renderer; got " +
                renderers.joinToString { it.name },
            ffmpeg
        )
        assertEquals(C.TRACK_TYPE_AUDIO, ffmpeg!!.trackType)
    }

    /**
     * The strongest static claim the code makes: the renderer Media3 will actually run reports a
     * plain DTS 5.1 track as fully handled. This is what turns "picture, no sound" into sound, and
     * it covers the parts [androidx.media3.decoder.ffmpeg.FfmpegLibrary.supportsFormat] alone does
     * not (channel layout, sample rate, the decoder being openable).
     */
    @Test
    fun ffmpegRendererSupportsADtsTrack() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderer = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .createRenderers(
                Handler(Looper.getMainLooper()),
                object : VideoRendererEventListener {},
                object : AudioRendererEventListener {},
                object : TextOutput {
                    override fun onCues(cueGroup: CueGroup) = Unit
                },
                object : MetadataOutput {
                    override fun onMetadata(metadata: Metadata) = Unit
                }
            )
            .first { it.javaClass.name == "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer" }
            as androidx.media3.exoplayer.BaseRenderer

        val format = androidx.media3.common.Format.Builder()
            .setId("1")
            .setSampleMimeType(MimeTypes.AUDIO_DTS)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()
        val capabilities = runCatching { renderer.supportsFormat(format) }
            .getOrElse { throw AssertionError("FfmpegAudioRenderer rejected audio/vnd.dts: $it") }
        // `Renderer.supportsFormat` is inherited from RendererCapabilities and packs three
        // answers into one int (format support | adaptive support << 3 | tunneling << 6), so the
        // format verdict has to be unpacked - comparing the raw word with C.FORMAT_HANDLED fails
        // with values like 172, which *does* mean FORMAT_HANDLED.
        val formatSupport = androidx.media3.exoplayer.RendererCapabilities.getFormatSupport(capabilities)
        assertEquals(
            "FfmpegAudioRenderer should fully handle a DTS 5.1 track (packed capabilities $capabilities)",
            C.FORMAT_HANDLED,
            formatSupport
        )
    }

    @Test
    fun probeReportsSoftwareDecodingForTracksWithoutHardwareSupport() {
        // rendererSupportsTrack is Media3's own Tracks.Group.isTrackSupported(), which is true
        // here precisely because the FFmpeg renderer above accepts the track. Passing false would
        // describe a build without the decoder library, which is the UNSUPPORTED case below.
        val dts = AudioDecodeSupport.decide(
            mimeType = MimeTypes.AUDIO_DTS,
            rendererSupportsTrack = true
        )
        assertEquals(AudioDecodeMode.SOFTWARE, dts?.mode)
        assertEquals("DTS", dts?.codecLabel)

        val truehd = AudioDecodeSupport.decide(
            mimeType = MimeTypes.AUDIO_TRUEHD,
            rendererSupportsTrack = true
        )
        assertEquals(AudioDecodeMode.SOFTWARE, truehd?.mode)
        assertEquals("Dolby TrueHD", truehd?.codecLabel)

        // And the same track on a build without libffmpegJNI.so: Media3 finds no renderer, so the
        // player must warn instead of playing a silent picture.
        assertEquals(
            AudioDecodeMode.UNSUPPORTED,
            AudioDecodeSupport.decide(
                mimeType = MimeTypes.AUDIO_DTS_HD,
                rendererSupportsTrack = false
            )?.mode
        )
    }

    /** Mirrors [AudioDecodeSupport]'s own registry scan, so the test cannot pass by accident. */
    private fun hardwareCanDecode(mimeType: String): Boolean {
        val lowered = mimeType.lowercase()
        return MediaCodecList(0).codecInfos.any { info ->
            info.supportedTypes.any { it.lowercase() == lowered }
        }
    }
}
