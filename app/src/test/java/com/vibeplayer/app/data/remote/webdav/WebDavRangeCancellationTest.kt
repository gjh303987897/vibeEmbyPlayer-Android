package com.vibeplayer.app.data.remote.webdav

import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class WebDavRangeCancellationTest {
    @Test
    fun `cancelling a range also interrupts response body consumption`() = runBlocking {
        MockWebServer().use { server ->
            val factory = OkHttpClientFactory()
            val client = WebDavClient(factory)
            val config = ServerConfig("dav", "DAV", server.url("/").toString(), "user", ServiceType.WEBDAV)
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 512-1049087/2097152").setHeader("ETag", "stable")
                .setBody(Buffer().write(ByteArray(1024 * 1024)))
                .throttleBody(1024, 100, TimeUnit.MILLISECONDS))
            val read = async(Dispatchers.IO) {
                client.downloadRange(config, "secret", "video.m3u8sp", 512, 1048576, 2097152, "stable")
            }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("bytes=512-1049087", request.getHeader("Range"))
            assertEquals("stable", request.getHeader("If-Range"))
            delay(150) // Headers have arrived, but the throttled body is still being read.
            withTimeout(1_000) { read.cancelAndJoin() }
            withTimeout(2_000) {
                while (factory.client(false).dispatcher.runningCallsCount() > 0) delay(10)
            }
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-3/4")
                .setHeader("ETag", "new").setBody("test"))
            assertArrayEquals("test".toByteArray(), client.downloadRange(config, "secret", "new", 0, 4, 4, "new").getOrThrow())
        }
    }
}
