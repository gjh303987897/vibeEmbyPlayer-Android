package com.vibeplayer.app.player

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsVariantsTest {
    private val base = "https://example.com/redirect/master.m3u8?auth=fixture".toHttpUrl()
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="main",URI="audio.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs.m3u8"
        #EXT-X-SESSION-KEY:METHOD=AES-128,URI="key"
        #EXT-X-STREAM-INF:BANDWIDTH=500000,AVERAGE-BANDWIDTH=400000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="audio",SUBTITLES="subs"
        low/video.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO="audio",SUBTITLES="subs"
        ../high.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=100000,URI="iframe.m3u8"
    """.trimIndent()

    @Test fun `variants use redirected base url and parse quoted codec commas and average bandwidth`() {
        val variants = parseHlsVariants(master, base)
        assertEquals(2, variants.size)
        assertEquals(HlsVariant("https://example.com/redirect/low/video.m3u8", 640, 360, 400_000), variants[0])
        assertEquals(HlsVariant("https://example.com/high.m3u8", 1920, 1080, 4_000_000), variants[1])
    }

    @Test fun `filtering really leaves one video variant and retains external audio subtitles and keys`() {
        val chosen = "https://example.com/high.m3u8"
        val filtered = selectHlsVariant(master, base, HlsVariant(chosen))
        assertEquals(listOf(chosen), parseHlsVariants(filtered, base).map { it.url })
        assertTrue(filtered.contains("URI=\"audio.m3u8\""))
        assertTrue(filtered.contains("URI=\"subs.m3u8\""))
        assertTrue(filtered.contains("#EXT-X-SESSION-KEY"))
        assertTrue(!filtered.contains("#EXT-X-I-FRAME-STREAM-INF"))
    }

    @Test fun `ordinary media playlists and expired variant selections are passed through`() {
        val media = "#EXTM3U\n#EXTINF:5,\nchunk.ts\n#EXT-X-ENDLIST\n"
        assertEquals(media, selectHlsVariant(media, base, HlsVariant("https://example.com/high.m3u8")))
        assertEquals(master, selectHlsVariant(master, base, HlsVariant("https://example.com/removed.m3u8")))
        assertEquals(master, selectHlsVariant(master, base, null))
    }

    @Test fun `rotating signed urls still retain the selected lowest resolution`() {
        val selected = HlsVariant("https://example.com/redirect/low/video.m3u8?old", 640, 360, 400_000)
        val changed = master.replace("low/video.m3u8", "low/video.m3u8?new-token=fixture")
        assertEquals(listOf("https://example.com/redirect/low/video.m3u8?new-token=fixture"),
            parseHlsVariants(selectHlsVariant(changed, base, selected), base).map { it.url })
    }
}
