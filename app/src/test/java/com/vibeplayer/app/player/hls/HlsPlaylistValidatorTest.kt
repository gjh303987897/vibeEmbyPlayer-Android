package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslDocument
import com.vibeplayer.app.domain.tssl.TsslManifest
import com.vibeplayer.app.domain.tssl.TsslSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistValidatorTest {
    private val identifier = "A".repeat(4096)
    private val document = TsslDocument(
        "TSSL", 2, "AES-256-GCM", identifier, "00".repeat(32), null,
        manifests = listOf(TsslManifest("variants/child.m3u8", "11".repeat(32))),
        segments = listOf(TsslSegment("segments/a.ts", ByteArray(32))),
        resources = mapOf("subtitles/zh.vtt" to "22".repeat(32))
    )

    @Test
    fun `accepts registered relative paths queries and child traversal inside root`() {
        assertEquals("segments/a.ts", resolvePlaylistUri("../segments/a.ts?token=x", "variants/child.m3u8"))
        val child = """#EXTM3U
#EXT-X-MEDIA:TYPE=SUBTITLES,URI="../subtitles/zh.vtt"
#EXTINF:1,
../segments/a.ts?token=x
""".toByteArray()
        assertTrue(validateHlsPlaylist(child, "variants/child.m3u8", document, root = false))
    }

    @Test
    fun `rejects external absolute escaping malformed and unregistered URIs`() {
        listOf(
            "https://evil.example/a.ts", "//evil.example/a.ts", "/segments/a.ts",
            "../../escape.ts", "segments/missing.ts"
        ).forEach { uri ->
            val playlist = "#EXTM3U\n#EXTINF:1,\n$uri\n".toByteArray()
            assertFalse(uri, validateHlsPlaylist(playlist, "index.m3u8s", document, root = false))
        }
        assertFalse(validateHlsPlaylist(
            "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=segments/a.ts\n".toByteArray(),
            "index.m3u8s", document, root = false
        ))
    }
}
