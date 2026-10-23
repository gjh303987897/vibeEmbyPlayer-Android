package com.vibeplayer.app.data.remote

import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CredentialBoundaryTest {
    @Test fun `tokens and cookies do not cross host or port redirects`() {
        for (header in listOf("X-Emby-Token", "X-MediaBrowser-Token", "Cookie")) {
            MockWebServer().use { origin -> MockWebServer().use { target ->
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/collect")))
                target.enqueue(MockResponse().setBody("leak"))
                val request = Request.Builder().url(origin.url("/image")).header(header, "synthetic-secret").build()
                assertBlocked { OkHttpClientFactory().client(false).newCall(request).execute().close() }
                assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
            } }
        }
    }

    @Test fun `307 and 308 cannot forward login passwords`() {
        for (status in listOf(307, 308)) MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.enqueue(MockResponse().setResponseCode(status).setHeader("Location", target.url("/collect")))
            target.enqueue(MockResponse().setBody("leak"))
            val request = Request.Builder().url(origin.url("/login")).post("{\"Pw\":\"synthetic-secret\"}".toRequestBody()).build()
            assertBlocked { OkHttpClientFactory().client(false).newCall(request).execute().close() }
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun `same origin redirects retain authentication and public CDN redirects work`() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/new-image"))
            origin.enqueue(MockResponse().setBody("image"))
            val client = OkHttpClientFactory().client(false)
            client.newCall(Request.Builder().url(origin.url("/image")).header("X-Emby-Token", "synthetic-secret").build()).execute().use {
                assertEquals("image", it.body!!.string())
            }
            origin.takeRequest(); assertEquals("synthetic-secret", origin.takeRequest().getHeader("X-Emby-Token"))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/video")))
            target.enqueue(MockResponse().setBody("video"))
            client.newCall(Request.Builder().url(origin.url("/video")).build()).execute().use { assertEquals("video", it.body!!.string()) }
        } }
    }

    @Test fun `Digest is not calculated for a redirected foreign authority`() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/challenge")))
            target.enqueue(digestChallenge())
            val server = ServerConfig("dav", "DAV", origin.url("/").toString(), "synthetic-user", ServiceType.WEBDAV)
            assertTrue(WebDavClient(OkHttpClientFactory()).download(server, "synthetic-password", "file").isFailure)
            assertTrue(origin.takeRequest().getHeader("Authorization")!!.startsWith("Basic "))
            assertNull(target.takeRequest().getHeader("Authorization"))
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun `same origin Digest challenge remains supported`() = runBlocking {
        MockWebServer().use { origin ->
            origin.enqueue(digestChallenge()); origin.enqueue(MockResponse().setBody("data"))
            val server = ServerConfig("dav", "DAV", origin.url("/").toString(), "synthetic-user", ServiceType.WEBDAV)
            assertEquals("data", WebDavClient(OkHttpClientFactory()).download(server, "synthetic-password", "file").getOrThrow().toString(Charsets.UTF_8))
            origin.takeRequest(); assertTrue(origin.takeRequest().getHeader("Authorization")!!.startsWith("Digest "))
        }
    }

    @Test fun `remote HTTP credentials are blocked unless explicitly approved`() {
        MockWebServer().use { origin ->
            origin.start()
            val url = origin.url("/api").newBuilder().host("lan.example.test").build()
            val factory = OkHttpClientFactory()
            val dns = object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            }
            val client = factory.client(false).newBuilder().dns(dns).build()
            val request = Request.Builder().url(url).header("Authorization", "Basic synthetic-secret").build()
            assertBlocked { client.newCall(request).execute().close() }
            assertNull(origin.takeRequest(100, TimeUnit.MILLISECONDS))
            origin.enqueue(MockResponse().setBody("approved"))
            factory.client(false, true).newBuilder().dns(dns).build()
                .newCall(request).execute().use { assertEquals("approved", it.body!!.string()) }
        }
    }

    @Test fun `HTTPS origin cannot downgrade on a follow up`() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.enableLocalHttps()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/downgrade")))
            val client = OkHttpClientFactory().client(true, true)
            assertBlocked { client.newCall(Request.Builder().url(origin.localHttpsUrl("/secure")).build()).execute().close() }
            assertEquals(1, origin.requestCount)
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun `certificate opt in does not accept a mismatched hostname`() {
        MockWebServer().use { origin ->
            origin.enableLocalHttps()
            val client = OkHttpClientFactory().client(true)
            assertBlocked { OkHttpClientFactory().client(false).newCall(Request.Builder().url(origin.localHttpsUrl("/secure")).build()).execute().close() }
            assertEquals(0, origin.requestCount)
            origin.enqueue(MockResponse().setBody("secure"))
            client.newCall(Request.Builder().url(origin.localHttpsUrl("/secure")).build()).execute().use { assertEquals("secure", it.body!!.string()) }
            val wrongName = origin.localHttpsUrl("/secure").newBuilder().host("127.0.0.1").build()
            assertBlocked { client.newCall(Request.Builder().url(wrongName).build()).execute().close() }
            assertEquals(1, origin.requestCount)
        }
    }

    private fun digestChallenge() = MockResponse().setResponseCode(401)
        .setHeader("WWW-Authenticate", "Digest realm=\"synthetic\", nonce=\"test-nonce\", qop=\"auth\"")

    private fun assertBlocked(action: () -> Unit) {
        try { action(); fail("Credential boundary should reject this request") } catch (_: IOException) { }
    }
}
