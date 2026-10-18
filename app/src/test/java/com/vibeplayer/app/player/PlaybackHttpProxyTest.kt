package com.vibeplayer.app.player

import com.vibeplayer.app.util.UserErrorKind
import com.vibeplayer.app.util.UserMessageFormatter
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHttpProxyTest {
    private val client = OkHttpClient()

    @Test
    fun `bracketed media filenames survive proxy creation and range requests`() {
        val filename = "Perfect.Crown.S01E05[EZTVx.to].mkv"
        val media = byteArrayOf(1, 2, 3, 4)
        upstream { request, _ ->
            assertEquals("/video/perfect_crown/$filename", request.requestUrl!!.encodedPath)
            assertEquals("bytes=1-3", request.getHeader("Range"))
            response(206, media.copyOfRange(1, 4)).addHeader("Content-Range", "bytes 1-3/4")
        }.use { source ->
            val url = "${source.url}/video/perfect_crown/$filename".toHttpUrl()
            PlaybackHttpProxy(client, url, emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).header("Range", "bytes=1-3").build())
                    .execute().use { response ->
                        assertEquals(206, response.code)
                        assertArrayEquals(media.copyOfRange(1, 4), response.body!!.bytes())
                        assertEquals("diagnostics counts upstream media bytes", 3L, proxy.receivedBytes)
                    }
            }
        }
    }

    @Test
    fun `upstream authentication and missing files retain their failure category`() {
        for ((status, kind) in listOf(401 to UserErrorKind.AUTHENTICATION, 404 to UserErrorKind.NOT_FOUND)) {
            upstream { _, _ -> MockResponse().setResponseCode(status) }.use { source ->
                PlaybackHttpProxy(client, "${source.url}/video.mkv".toHttpUrl(), emptyMap()).use { proxy ->
                    client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { response ->
                        assertEquals(status, response.code)
                        assertEquals(kind, proxy.failure)
                        assertEquals(kind, UserMessageFormatter.classify("PLAYBACK_HTTP_${proxy.failure}").kind)
                    }
                }
            }
        }
    }

    @Test
    fun `upstream timeout survives the loopback gateway response`() {
        upstream { _, _ -> MockResponse().setBody("video").setHeadersDelay(1, TimeUnit.SECONDS) }.use { source ->
            val upstreamClient = client.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS).build()
            PlaybackHttpProxy(upstreamClient, "${source.url}/video.mp4".toHttpUrl(), emptyMap()).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).build()).execute().use { response ->
                    assertEquals(502, response.code)
                    assertEquals(UserErrorKind.TIMEOUT, proxy.failure)
                }
            }
        }
    }

    @Test
    fun `seeking preserves authentication query bytes and range metadata`() {
        val media = ByteArray(64) { it.toByte() }
        upstream { request, _ ->
            assertEquals("session=fixture", request.requestUrl!!.encodedQuery)
            assertEquals("fixture-token", request.getHeader("Authorization"))
            assertEquals("bytes=9-17", request.getHeader("Range"))
            response(206, media.copyOfRange(9, 18)).addHeader("Content-Range", "bytes 9-17/64")
        }.use { source ->
            PlaybackHttpProxy(client, "${source.url}/file.mp4?session=fixture".toHttpUrl(), mapOf("Authorization" to "fixture-token")).use { proxy ->
                client.newCall(Request.Builder().url(proxy.playbackUrl).header("Range", "bytes=9-17").build()).execute().use { response ->
                    assertEquals(206, response.code)
                    assertEquals("bytes 9-17/64", response.header("Content-Range"))
                    assertArrayEquals(media.copyOfRange(9, 18), response.body!!.bytes())
                }
            }
        }
    }

    @Test
    fun `HLS redirect resolves nested resources and isolates CDN credentials`() {
        upstream { request, _ ->
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Emby-Token"))
            response(200, byteArrayOf(7, 8, 9))
        }.use { cdn ->
            upstream { request, source ->
                assertEquals("fixture-token", request.getHeader("Authorization"))
                when (request.requestUrl!!.encodedPath) {
                    "/start" -> MockResponse().setResponseCode(302).addHeader("Location", "/hls/master.m3u8")
                    "/hls/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nchild.m3u8\n")
                    "/hls/child.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"${source.url}/key\"\n#EXTINF:1,\n${source.url}/redirect-segment\n")
                    "/key" -> response(200, ByteArray(16) { 2 })
                    "/redirect-segment" -> MockResponse().setResponseCode(302).addHeader("Location", "${cdn.url}/segment.ts")
                    else -> MockResponse().setResponseCode(404)
                }
            }.use { source ->
                PlaybackHttpProxy(client, "${source.url}/start".toHttpUrl(), mapOf("Authorization" to "fixture-token", "X-Emby-Token" to "fixture-token")).use { proxy ->
                    val master = fetch(proxy.playbackUrl).toString(Charsets.UTF_8)
                    val childUrl = master.lineSequence().first { it.startsWith("http://") }
                    val child = fetch(childUrl).toString(Charsets.UTF_8)
                    val keyUrl = Regex("URI=\"([^\"]+)\"").find(child)!!.groupValues[1]
                    assertArrayEquals(ByteArray(16) { 2 }, fetch(keyUrl))
                    val segment = child.lineSequence().first { it.startsWith("http://") }
                    assertArrayEquals(byteArrayOf(7, 8, 9), fetch(segment))
                }
            }
        }
    }

    @Test
    fun `DASH representation base resolves inherited segment template`() {
        upstream { request, _ ->
            when (request.requestUrl!!.encodedPath) {
                "/mpd/stream.mpd" -> MockResponse().addHeader("Content-Type", "application/dash+xml").setBody(
                    "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><AdaptationSet><SegmentTemplate media=\"seg-\$Number\$.m4s\"/><Representation id=\"v\"><BaseURL>../video/</BaseURL></Representation></AdaptationSet></Period></MPD>"
                )
                "/video/seg-12.m4s" -> response(200, byteArrayOf(4, 5, 6))
                else -> MockResponse().setResponseCode(404)
            }
        }.use { source ->
            PlaybackHttpProxy(client, "${source.url}/mpd/stream.mpd".toHttpUrl(), emptyMap()).use { proxy ->
                val mpd = fetch(proxy.playbackUrl).toString(Charsets.UTF_8)
                val bases = Regex("<BaseURL>([^<]+)</BaseURL>").findAll(mpd).map { it.groupValues[1] }.toList()
                assertArrayEquals(byteArrayOf(4, 5, 6), fetch(bases.last() + "seg-12.m4s"))
            }
        }
    }

    @Test
    fun `selected HLS quality is enforced over HTTP while external audio remains accessible`() {
        upstream { request, _ ->
            when (request.requestUrl!!.encodedPath) {
                "/start" -> MockResponse().setResponseCode(302).addHeader("Location", "/master.m3u8")
                "/master.m3u8" -> MockResponse().setBody("#EXTM3U\n" +
                    "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"main\",URI=\"audio.m3u8\"\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,AUDIO=\"a\"\nlow.m3u8\n" +
                    "#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,AUDIO=\"a\"\nhigh.m3u8\n")
                "/low.m3u8" -> MockResponse().setBody("low route")
                "/high.m3u8" -> MockResponse().setBody("high route")
                "/audio.m3u8" -> MockResponse().setBody("external audio")
                else -> MockResponse().setResponseCode(404)
            }
        }.use { source ->
            for (quality in listOf("high", "low")) {
                PlaybackHttpProxy(client, "${source.url}/start".toHttpUrl(), emptyMap(), HlsVariant("${source.url}/$quality.m3u8")).use { proxy ->
                    val master = fetch(proxy.playbackUrl).toString(Charsets.UTF_8)
                    val videoUrls = master.lineSequence().filter { it.startsWith("http://") }.toList()
                    assertEquals(1, videoUrls.size)
                    assertEquals("$quality route", fetch(videoUrls.single()).toString(Charsets.UTF_8))
                    val audio = Regex("URI=\"([^\"]+)\"").find(master)!!.groupValues[1]
                    assertEquals("external audio", fetch(audio).toString(Charsets.UTF_8))
                }
            }
        }
    }

    private fun fetch(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
        assertEquals(200, response.code)
        response.body!!.bytes()
    }

    private fun response(code: Int, body: ByteArray): MockResponse =
        MockResponse().setResponseCode(code).setBody(Buffer().write(body))

    private fun upstream(handle: (RecordedRequest, Upstream) -> MockResponse): Upstream = Upstream(handle)

    private class Upstream(handle: (RecordedRequest, Upstream) -> MockResponse) : AutoCloseable {
        private val server = MockWebServer()
        @Volatile private var failure: Throwable? = null
        val url: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    handle(request, this@Upstream)
                } catch (error: Throwable) {
                    failure = error
                    MockResponse().setResponseCode(500)
                }
            }
            server.start()
            url = server.url("/").toString().removeSuffix("/")
        }

        override fun close() {
            try { server.shutdown() } finally { failure?.let { throw it } }
        }
    }
}
