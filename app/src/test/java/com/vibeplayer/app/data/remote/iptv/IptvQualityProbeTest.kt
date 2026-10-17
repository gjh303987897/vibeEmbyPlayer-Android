package com.vibeplayer.app.data.remote.iptv

import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.repository.IptvStreamRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.IptvChannel
import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.rankIptvRoutes
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvQualityProbeTest {
    private fun probe(inspect: (ByteArray) -> IptvProbeResult = { IptvProbeResult() }) =
        IptvQualityProbe(OkHttpClientFactory(), inspect)

    @Test fun `range rejecting servers are retried without range and resolve redirected variant URLs`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/hls/master.m3u8"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n"))
            server.start()
            val result = probe().probe(server.url("/start").toString(), false)
            assertEquals(true, result.available)
            assertEquals(server.url("/hls/low.m3u8").toString(), result.variants.single().url)
            assertEquals("bytes=0-524287", server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun `response bodies are bounded when a server ignores range`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_048_576) { 1 })))
            server.start()
            var sampled = 0
            val result = probe { sampled = it.size; IptvProbeResult(width = 640, height = 360) }
                .probe(server.url("/live.ts").toString(), false)
            assertEquals(524_288, sampled)
            assertEquals(360, result.height)
            assertEquals(true, result.available)
        }
    }

    @Test fun `slow live media retains useful partial bytes instead of being marked offline`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_048_576) { 1 }))
                .throttleBody(1024, 1, TimeUnit.SECONDS))
            server.start()
            var sampled = 0
            val result = probe { sampled = it.size; IptvProbeResult(width = 320, height = 180) }
                .probe(server.url("/slow.ts").toString(), false)
            assertTrue(sampled in 1 until 524_288)
            assertEquals(180, result.height)
            assertEquals(true, result.available)
        }
    }

    @Test fun `leaving playback cancels a pending HTTP sample without running metadata extraction`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(1_048_576) { 1 }))
                .throttleBody(1024, 1, TimeUnit.SECONDS))
            server.start()
            var inspected = false
            val began = System.nanoTime()
            try {
                withTimeout(250) {
                    probe { inspected = true; IptvProbeResult() }.probe(server.url("/slow.ts").toString(), false)
                }
                throw AssertionError("Probe must propagate cancellation")
            } catch (_: TimeoutCancellationException) {
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2_000)
                assertFalse(inspected)
            }
        }
    }

    @Test fun `repository caches per route and a refresh updates the qualities used for automatic selection`() = runBlocking {
        MockWebServer().use { server ->
            fun master(high: Int) = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\nlow.m3u8\n" +
                "#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x$high\nhigh.m3u8\n"
            server.enqueue(MockResponse().setBody(master(1080)))
            server.enqueue(MockResponse().setBody(master(720)))
            server.start()
            val channel = IptvChannel(listOf(IptvChannelEntity("id", "p", "Channel", "News", "", server.url("/master").toString(), 0)))
            val repository = IptvStreamRepository(probe())
            val initial = repository.resolve(channel, false)
            assertEquals(1080, rankIptvRoutes(initial, IptvQualityPreference.HIGHEST).first().height)
            assertEquals(360, rankIptvRoutes(initial, IptvQualityPreference.LOWEST).first().height)
            assertEquals(initial, repository.resolve(channel, false))
            assertEquals(1, server.requestCount)
            val refreshed = repository.resolve(channel, false, refresh = true)
            assertEquals(720, rankIptvRoutes(refreshed, IptvQualityPreference.HIGHEST).first().height)
            assertEquals(2, server.requestCount)
        }
    }
}
