package com.vibeplayer.app.player

import org.junit.Assert.*
import org.junit.Test
import tv.danmaku.ijk.media.player.IjkMediaMeta
import tv.danmaku.ijk.media.player.misc.IMediaFormat
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import tv.danmaku.ijk.media.player.misc.IjkMediaFormat

class PlaybackMediaInfoTest {
    @Test fun `selected streams preserve fractional fps and 64 bit bitrate`() {
        val info = readPlaybackMediaInfo(listOf(
            track(3, ITrackInfo.MEDIA_TRACK_TYPE_VIDEO, mapOf("codec_name" to "hevc", "codec_profile" to "Main 10",
                "width" to "3840", "height" to "2160", "fps_num" to "30000", "fps_den" to "1001",
                "bitrate" to "3000000000", "codec_pixel_format" to "yuv420p10le")),
            track(7, ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, mapOf("codec_name" to "aac", "bitrate" to "128000",
                "sample_rate" to "48000", "channel_layout" to "3"))
        ), selectedVideo = 3, selectedAudio = 7)
        assertEquals(3840, info.video?.width)
        assertEquals(2160, info.video?.height)
        assertEquals(30000.0 / 1001, info.video!!.frameRate!!, .00001)
        assertEquals(3_000_000_000L, info.video?.bitrate)
        assertEquals("Main 10", info.video?.profile)
        assertEquals("yuv420p10le", info.video?.pixelFormat)
        assertEquals(AudioStreamInfo("aac", 128_000, 48_000, 2), info.audio)
    }

    @Test fun `audio selection uses the current track rather than first available audio`() {
        val tracks = listOf(
            track(0, ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, mapOf("codec_name" to "aac", "bitrate" to "128000")),
            track(1, ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, mapOf("codec_name" to "ac3", "bitrate" to "640000"))
        )
        assertEquals("aac", readPlaybackMediaInfo(tracks, -1, 0).audio?.codec)
        val changed = readPlaybackMediaInfo(tracks, -1, 1)
        assertEquals("ac3", changed.audio?.codec)
        assertEquals(640_000L, changed.audio?.bitrate)
        assertNull(changed.video)
        assertNull(readPlaybackMediaInfo(tracks, -1, -1).audio)
    }

    @Test fun `missing invalid and zero values stay unknown without estimating rates`() {
        val info = readPlaybackMediaInfo(listOf(
            track(0, ITrackInfo.MEDIA_TRACK_TYPE_VIDEO, mapOf("codec_name" to "  ", "width" to "0", "height" to "-1",
                "fps_num" to "24", "fps_den" to "0", "tbr_num" to "24", "tbr_den" to "1", "bitrate" to "N/A")),
            track(1, ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, mapOf("bitrate" to "-1", "sample_rate" to "broken", "channel_layout" to "0"))
        ), 0, 1)
        assertEquals(VideoStreamInfo(null, null, null, null, null, null, null), info.video)
        assertEquals(AudioStreamInfo(null, null, null, null), info.audio)
    }

    @Test fun `absent or wrong type selected streams never reuse other metadata`() {
        val tracks = listOf(track(4, ITrackInfo.MEDIA_TRACK_TYPE_AUDIO, mapOf("codec_name" to "flac")))
        assertEquals(PlaybackMediaInfo(), readPlaybackMediaInfo(tracks, 4, 0))
        assertEquals(PlaybackMediaInfo(), readPlaybackMediaInfo(emptyList(), 0, 1))
    }

    private fun track(index: Int, type: Int, values: Map<String, String>): ITrackInfo {
        // The native index can have gaps even though the returned track array is compact.
        val format = object : IjkMediaFormat(IjkMediaMeta.IjkStreamMeta(index)) {
            override fun getString(name: String): String? = values[name]
            override fun getInteger(name: String): Int = values[name]?.toIntOrNull() ?: 0
        }
        return object : ITrackInfo {
            override fun getFormat(): IMediaFormat = format
            override fun getTrackType(): Int = type
            override fun getLanguage(): String = "und"
            override fun getInfoInline(): String = "fixture"
        }
    }
}
