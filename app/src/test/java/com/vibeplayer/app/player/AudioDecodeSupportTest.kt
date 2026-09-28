package com.vibeplayer.app.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decoder decisions for the audio formats that cause "video plays, no sound".
 *
 * The player streams `static=true` (no server transcoding), so what a track sounds like
 * depends entirely on which renderer the phone has. These are the combinations that must be
 * told apart, with the device decoder list and the FFmpeg availability injected so no real
 * device - or native library - is needed.
 */
class AudioDecodeSupportTest {

    private val noHardwareAudioDecoders = setOf(
        MimeTypes.AUDIO_AAC,
        MimeTypes.AUDIO_MP4,
        MimeTypes.AUDIO_VORBIS,
        MimeTypes.AUDIO_OPUS,
        MimeTypes.AUDIO_FLAC
    )

    /** Mirrors what `FfmpegLibrary.supportsFormat()` answers for the shipped decoder list. */
    private fun ffmpegKnows(mimeType: String): Boolean = mimeType in setOf(
        MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_VORBIS, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC,
        MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_AAC
    )

    private fun decide(
        mimeType: String?,
        hardware: Set<String> = noHardwareAudioDecoders,
        ffmpegAvailable: Boolean = true,
        rendererSupportsTrack: Boolean = true
    ): AudioDecodeInfo? = decideAudioDecode(
        mimeType = mimeType,
        rendererSupportsTrack = rendererSupportsTrack,
        hardwareMimeTypes = hardware,
        softDecoderAvailable = ffmpegAvailable,
        softDecoderSupports = ::ffmpegKnows
    )

    @Test
    fun `formats the phone decodes itself produce no notice`() {
        listOf(
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_DTS_HD
        ).forEach { mime ->
            val info = decide(mime, hardware = setOf(mime))
            assertEquals("$mime should be handled by MediaCodec", AudioDecodeMode.HARDWARE, info?.mode)
        }
    }

    @Test
    fun `licensed formats fall back to on-device ffmpeg decoding`() {
        listOf(
            MimeTypes.AUDIO_AC3 to "Dolby Digital (AC-3)",
            MimeTypes.AUDIO_E_AC3 to "Dolby Digital Plus (E-AC-3)",
            MimeTypes.AUDIO_E_AC3_JOC to "Dolby Atmos (E-AC-3 JOC)",
            MimeTypes.AUDIO_TRUEHD to "Dolby TrueHD",
            MimeTypes.AUDIO_DTS to "DTS",
            MimeTypes.AUDIO_DTS_HD to "DTS-HD Master Audio"
        ).forEach { (mime, label) ->
            val info = decide(mime)
            assertEquals("$mime should be soft-decoded", AudioDecodeMode.SOFTWARE, info?.mode)
            assertEquals(mime, info?.mimeType)
            assertEquals(label, info?.codecLabel)
        }
    }

    @Test
    fun `without the client decoder library the track is reported as unplayable`() {
        // No MediaCodec decoder, no FFmpeg: Media3 finds no renderer at all and drops the
        // track, which is precisely the silent-video case the notice exists to explain.
        val info = decide(MimeTypes.AUDIO_DTS_HD, ffmpegAvailable = false, rendererSupportsTrack = false)
        assertEquals(AudioDecodeMode.UNSUPPORTED, info?.mode)
        assertEquals("DTS-HD Master Audio", info?.codecLabel)
    }

    @Test
    fun `a track no renderer accepts is unsupported even if the codec list mentions it`() {
        // MediaCodec advertises the MIME type, but the audio path cannot actually use it and
        // FFmpeg is not built in: Media3 selects no decoder, so the user gets silence.
        val info = decide(
            mimeType = MimeTypes.AUDIO_AC3,
            hardware = setOf(MimeTypes.AUDIO_AC3),
            ffmpegAvailable = false,
            rendererSupportsTrack = false
        )
        assertEquals(AudioDecodeMode.UNSUPPORTED, info?.mode)
    }

    @Test
    fun `ffmpeg cannot help with formats it has no decoder for`() {
        val wavpack = "audio/wavpack" // not in FfmpegLibrary.getCodecName()
        assertEquals(
            AudioDecodeMode.UNSUPPORTED,
            decide(wavpack, rendererSupportsTrack = false)?.mode
        )
        // ...and the label still tells the user which format was meant.
        assertEquals("WAVPACK", decide(wavpack, rendererSupportsTrack = false)?.codecLabel)
    }

    @Test
    fun `a supported track from an unexpected renderer produces no notice`() {
        // Neither our MediaCodec scan nor FFmpeg claims it, yet Media3 found a renderer for it
        // (another extension, or a codec list quirk): nothing worth interrupting the user for.
        assertNull(decide("audio/x-unknown-hw"))
    }

    @Test
    fun `video text and missing mime types are ignored`() {
        assertNull(decide(null))
        assertNull(decide(MimeTypes.VIDEO_H265))
        assertNull(decide(MimeTypes.TEXT_VTT))
        assertNull(decide(MimeTypes.APPLICATION_MP4))
    }

    @Test
    fun `dts express and dts-x keep their own names`() {
        assertEquals("DTS Express", audioCodecLabel(MimeTypes.AUDIO_DTS_EXPRESS))
        assertEquals("DTS:X", audioCodecLabel(MimeTypes.AUDIO_DTS_X))
    }
}
