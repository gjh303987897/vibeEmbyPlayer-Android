package com.vibeplayer.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.player.WebDavTsslSeekFixture
import com.vibeplayer.app.tssl.TsslDeviceHarness
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialBoundaryDeviceTest {
    @Test fun partialServiceReorderKeepsHiddenAndConcurrentUpdates() = runBlocking {
        SecurityDeviceHarness().use { h ->
            val a = ServerConfig("a", "A", "https://example.test", "", ServiceType.LINK)
            val hidden = a.copy(id = "hidden", name = "Private", privateMode = true)
            val b = a.copy(id = "b", name = "B")
            listOf(a, hidden, b).forEach { h.services.upsert(it) }
            h.services.reorder(listOf("b", "a"))
            assertEquals(listOf(b, hidden, a), h.services.services.first())
            val edited = b.copy(name = "Latest B", privateMode = true)
            val added = a.copy(id = "added", name = "Concurrent")
            coroutineScope {
                listOf(async { h.services.upsert(edited) }, async { h.services.upsert(added) },
                    async { h.services.reorder(listOf("a", "missing", "a", "b")) }).forEach { it.await() }
            }
            val actual = h.services.services.first()
            assertEquals(setOf("a", "hidden", "b", "added"), actual.map { it.id }.toSet())
            assertEquals(4, actual.size)
            assertEquals(edited, actual.first { it.id == "b" })
            assertEquals(hidden, actual[1])
        }
    }

    @Test fun credentialsAndLoginBodiesNeverReachRedirectedAuthority() = runBlocking {
        val factory = OkHttpClientFactory()
        MockWebServer().use { origin -> MockWebServer().use { target ->
            for (header in listOf("X-Emby-Token", "X-MediaBrowser-Token", "Cookie")) {
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/collect")))
                blocked { factory.client(false).newCall(Request.Builder().url(origin.url("/image"))
                    .header(header, "synthetic-secret").build()).execute().close() }
            }
            for (status in listOf(307, 308)) {
                origin.enqueue(MockResponse().setResponseCode(status).setHeader("Location", target.url("/login")))
                blocked { factory.client(false).newCall(Request.Builder().url(origin.url("/login"))
                    .post("synthetic-password".toRequestBody()).build()).execute().close() }
            }
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/challenge")))
            target.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate",
                "Digest realm=\"synthetic\", nonce=\"test-nonce\", qop=\"auth\""))
            val server = ServerConfig("synthetic", "DAV", origin.url("/").toString(), "test", ServiceType.WEBDAV)
            assertTrue(WebDavClient(factory).download(server, "synthetic-password", "file").isFailure)
            assertNull(target.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
            assertEquals("only an anonymous Digest challenge request may reach the other port", 1, target.requestCount)
        } }
    }

    @Test fun tlsHostnameAndDowngradeChecksRemainEnabledOnAndroid() {
        TsslDeviceHarness().use { h -> MockWebServer().use { secure -> MockWebServer().use { plain ->
            h.enableHttps(secure)
            val url = secure.url("/secure").newBuilder().host("localhost").build()
            val factory = OkHttpClientFactory()
            blocked { factory.client(false).newCall(Request.Builder().url(url).build()).execute().close() }
            assertEquals(0, secure.requestCount)
            secure.enqueue(MockResponse().setBody("secure"))
            factory.client(true).newCall(Request.Builder().url(url).build()).execute().use {
                assertEquals("secure", it.body!!.string())
            }
            blocked { factory.client(true).newCall(Request.Builder().url(url.newBuilder().host("127.0.0.1").build())
                .build()).execute().close() }
            assertEquals(1, secure.requestCount)
            secure.enqueue(MockResponse().setResponseCode(302).setHeader("Location", plain.url("/downgrade")))
            blocked { factory.client(true, true).newCall(Request.Builder().url(url).build()).execute().close() }
            assertNull(plain.takeRequest(100, TimeUnit.MILLISECONDS))
        } } }
    }

    @Test fun remoteHttpCredentialsRequireExplicitConsent() {
        MockWebServer().use { origin ->
            val url = origin.url("/api").newBuilder().host("lan.example.test").build()
            val dns = object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }
            val request = Request.Builder().url(url).header("Authorization", "Basic synthetic").build()
            val factory = OkHttpClientFactory()
            blocked { factory.client(false).newBuilder().dns(dns).build().newCall(request).execute().close() }
            assertNull(origin.takeRequest(100, TimeUnit.MILLISECONDS))
            origin.enqueue(MockResponse().setBody("approved"))
            factory.client(false, true).newBuilder().dns(dns).build().newCall(request).execute().use {
                assertEquals("approved", it.body!!.string())
            }
        }
    }

    @Test fun encryptedLoopbackRejectsMissingAndWrongCapabilities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        WebDavTsslSeekFixture(instrumentation.targetContext, instrumentation.context).use { fixture ->
            val playback = fixture.prepare()
            val client = OkHttpClientFactory().client(false)
            val authorized = playback.playUrl.toHttpUrl()
            for (path in listOf("/index.m3u8", "/wrong/index.m3u8", "/segment_000.ts")) {
                client.newCall(Request.Builder().url(authorized.newBuilder().encodedPath(path).build()).build())
                    .execute().use { assertEquals(404, it.code) }
            }
            client.newCall(Request.Builder().url(authorized).build()).execute().use {
                assertEquals(200, it.code)
                assertTrue(it.body!!.string().startsWith("#EXTM3U"))
            }
        }
    }

    private fun blocked(action: () -> Unit) {
        try { action(); fail("Credential boundary should reject the request") } catch (_: IOException) { }
    }
}
