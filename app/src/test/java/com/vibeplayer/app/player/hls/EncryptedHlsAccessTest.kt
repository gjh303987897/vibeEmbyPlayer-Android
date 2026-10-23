package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument
import java.net.Socket
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class EncryptedHlsAccessTest {
    @Test fun `only this session capability can read manifest and ranges`() {
        val bytes = "#EXTM3U\n".toByteArray()
        val key = ByteArray(32) { 7 }
        val plain = "synthetic media bytes".toByteArray()
        val encrypted = TsslCrypto.encrypt(key, ByteArray(TsslCrypto.IV_BYTES) { 3 }, plain)
        var reads = 0
        val document = TsslDocument("TSSL", 2, "AES-256-GCM", "A".repeat(4096),
            TsslCrypto.sha256Hex(bytes), null, emptyList(), listOf(com.vibeplayer.app.domain.tssl.TsslSegment("video.ts", key)), emptyMap())
        val server = EncryptedHlsServer(document, HlsByteSource { reads++; Result.success(encrypted) }, "index.m3u8", bytes)
        val other = EncryptedHlsServer(document, HlsByteSource { error("Unauthorized source read") }, "index.m3u8", bytes)
        server.start(); other.start()
        try {
            val base = URI(server.baseUrl)
            val host = "127.0.0.1:${base.port}"
            assertTrue(request(base.port, "/index.m3u8", host).startsWith("HTTP/1.1 404"))
            assertTrue(request(base.port, "/guessed/index.m3u8", host).startsWith("HTTP/1.1 404"))
            assertTrue(request(base.port, "/video.ts", host).startsWith("HTTP/1.1 404"))
            assertTrue(request(base.port, "/guessed/video.ts", host).startsWith("HTTP/1.1 404"))
            assertEquals(0, reads)
            assertTrue(request(base.port, base.rawPath + "index.m3u8", "foreign.test:${base.port}").startsWith("HTTP/1.1 404"))
            assertTrue(request(base.port, URI(other.baseUrl).rawPath + "index.m3u8", host).startsWith("HTTP/1.1 404"))
            val valid = request(base.port, base.rawPath + "index.m3u8", host)
            assertTrue(valid.startsWith("HTTP/1.1 200")); assertTrue(valid.endsWith("#EXTM3U\n"))
            val range = request(base.port, base.rawPath + "index.m3u8", host, "Range: bytes=0-3\r\n")
            assertTrue(range.startsWith("HTTP/1.1 206")); assertTrue(range.endsWith("#EXT"))
            val segment = request(base.port, base.rawPath + "video.ts", host)
            assertTrue(segment.startsWith("HTTP/1.1 200")); assertTrue(segment.endsWith(plain.toString(Charsets.UTF_8)))
            assertEquals(1, reads)
            assertTrue(request(base.port, base.rawPath + "../index.m3u8", host).startsWith("HTTP/1.1 400"))
        } finally { server.close(); other.close() }
    }

    private fun request(port: Int, path: String, host: String, headers: String = ""): String = Socket("127.0.0.1", port).use {
        it.soTimeout = 2_000
        it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: $host\r\n$headers\r\n".toByteArray())
        it.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
