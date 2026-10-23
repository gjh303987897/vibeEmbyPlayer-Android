package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument
import com.vibeplayer.app.domain.tssl.TsslSegment
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Test

class EncryptedHlsCancellationTest {
    @Test
    fun `seek closes old response and cancels its source while later requests still work`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val server = server(HlsByteSource {
            entered.countDown()
            try { awaitCancellation() } finally { cancelled.countDown() }
        })
        server.start()
        try {
            val port = java.net.URI(server.baseUrl).port
            Socket("127.0.0.1", port).use { old ->
                old.soTimeout = 1_000
                old.getOutputStream().write("GET ${java.net.URI(server.baseUrl).rawPath}old.ts HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                server.interruptPendingReads()
                assertEquals(-1, old.getInputStream().read())
                assertTrue("seek must cancel the remote read", cancelled.await(1, TimeUnit.SECONDS))
            }
            Socket("127.0.0.1", port).use { next ->
                next.soTimeout = 1_000
                next.getOutputStream().write("GET ${java.net.URI(server.baseUrl).rawPath}index.m3u8 HTTP/1.1\r\nHost: localhost:$port\r\n\r\n".toByteArray())
                val response = next.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.endsWith("#EXTM3U\n"))
            }
        } finally { server.close() }
    }

    @Test
    fun `closing a session cancels remote reads and client sockets`() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val server = server(HlsByteSource {
            entered.countDown()
            try { awaitCancellation() } finally { cancelled.countDown() }
        })
        server.start()
        try {
            Socket("127.0.0.1", java.net.URI(server.baseUrl).port).use { socket ->
                socket.soTimeout = 1_000
                socket.getOutputStream().write("GET ${java.net.URI(server.baseUrl).rawPath}old.ts HTTP/1.1\r\nHost: localhost:${socket.port}\r\n\r\n".toByteArray())
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                server.close()
                assertEquals(-1, socket.getInputStream().read())
                assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            }
        } finally { server.close() }
    }

    private fun server(source: HlsByteSource) = EncryptedHlsServer(
        TsslDocument("TSSL", 2, "AES-256-GCM", "A".repeat(4096),
            TsslCrypto.sha256Hex("#EXTM3U\n".toByteArray()), null, emptyList(),
            listOf(TsslSegment("old.ts", ByteArray(32))), emptyMap()),
        source, "index.m3u8", "#EXTM3U\n".toByteArray())
}
