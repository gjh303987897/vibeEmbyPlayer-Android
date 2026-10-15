package com.vibeplayer.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDecodeSupportTest {
    @Test
    fun `IJK hardware decoder produces no software notice`() {
        val info = decideAudioDecode("aac", hardwareDecoder = true)
        assertEquals(AudioDecodeMode.HARDWARE, info?.mode)
        assertEquals("AAC", info?.codecLabel)
    }

    @Test
    fun `IJK FFmpeg path identifies lossless and surround codecs`() {
        listOf(
            "ac3" to "Dolby Digital (AC-3)",
            "eac3" to "Dolby Digital Plus (E-AC-3)",
            "truehd" to "Dolby TrueHD",
            "dts" to "DTS",
            "dts-hd" to "DTS-HD Master Audio"
        ).forEach { (codec, label) ->
            val info = decideAudioDecode(codec, hardwareDecoder = false)
            assertEquals(AudioDecodeMode.SOFTWARE, info?.mode)
            assertEquals(label, info?.codecLabel)
        }
    }

    @Test
    fun `missing codec decoder is reported unsupported`() {
        assertEquals(
            AudioDecodeMode.UNSUPPORTED,
            decideAudioDecode("unknown", hardwareDecoder = false, decoderAvailable = false)?.mode
        )
    }

    @Test
    fun `missing codec name is ignored`() {
        assertNull(decideAudioDecode(null, hardwareDecoder = false))
    }

    @Test
    fun `codec labels preserve DTS variants`() {
        assertEquals("DTS", audioCodecLabel("dts"))
        assertEquals("DTS-HD Master Audio", audioCodecLabel("dts-hd"))
    }
}
