package com.vibeplayer.app.data.remote

import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupHttpIoTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `WebDAV streams file uploads and downloads without changing bytes`() = runBlocking {
        MockWebServer().use { server ->
            server.enableLocalHttps()
            val client = WebDavClient(OkHttpClientFactory())
            val config = ServerConfig("backup", "test", server.localHttpsUrl("/dav/").toString(), "user", ServiceType.WEBDAV, trustSelfSignedCertificate = true)
            val bytes = ByteArray(256 * 1024) { it.toByte() }
            val input = temporary.newFile("input.zip").apply { writeBytes(bytes) }
            server.enqueue(MockResponse().setResponseCode(201))
            client.uploadBackup(config, "secret", "folder/tssl-backup-test.zip", input).getOrThrow()
            val put = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", put.method); assertEquals("/dav/folder/tssl-backup-test.zip", put.path)
            assertEquals(bytes.size.toString(), put.getHeader("Content-Length")); assertArrayEquals(bytes, put.body.readByteArray())
            server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(bytes), 4096))
            val output = temporary.newFile("output.zip")
            client.downloadBackup(config, "secret", "folder/tssl-backup-test.zip", output, bytes.size.toLong()).getOrThrow()
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertArrayEquals(bytes, output.readBytes())
        }
    }

    @Test fun `file upload can replay its stream after Digest authentication challenge`() = runBlocking {
        MockWebServer().use { server ->
            server.enableLocalHttps()
            val client = WebDavClient(OkHttpClientFactory())
            val config = ServerConfig("backup", "test", server.localHttpsUrl("/").toString(), "user", ServiceType.WEBDAV, trustSelfSignedCertificate = true)
            val file = temporary.newFile().apply { writeText("original backup bytes") }
            server.enqueue(MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", "Digest realm=\"backup\", nonce=\"test-nonce\", qop=\"auth\", algorithm=MD5"))
            server.enqueue(MockResponse().setResponseCode(204))
            client.uploadBackup(config, "secret", "test.tssl", file).getOrThrow()
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!; val second = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertArrayEquals(first.body.readByteArray(), second.body.readByteArray())
            assertTrue(second.getHeader("Authorization")!!.startsWith("Digest "))
        }
    }

    @Test fun `WebDAV key backups reject HTTP even with server consent`() = runBlocking {
        MockWebServer().use { server ->
            val client = WebDavClient(OkHttpClientFactory())
            val config = ServerConfig("backup", "test", server.url("/").toString(), "user", ServiceType.WEBDAV, allowInsecureHttp = true)
            val file = temporary.newFile().apply { writeText("synthetic key package") }
            assertTrue(client.uploadBackup(config, "synthetic-secret", "test.tssl", file).isFailure)
            assertTrue(client.downloadBackup(config, "synthetic-secret", "test.tssl", temporary.newFile(), 1024).isFailure)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `download rejects declared and chunked bodies exceeding bounds`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            listOf(MockResponse().setBody("too large"), MockResponse().setChunkedBody("too large", 1)).forEach { response ->
                server.enqueue(response)
                val request = Request.Builder().url(server.url("/file")).build()
                val context = coroutineContext
                try {
                    client.newCall(request).backupResponse { it.backupToFile(File(temporary.root, "bounded"), 4, context) }
                    fail("Size limit must be enforced")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun `cancelling a stalled body closes its file before returning`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(2, TimeUnit.SECONDS))
            val output = temporary.newFile("canceled")
            val entered = CompletableDeferred<Unit>()
            val job = async(Dispatchers.IO) {
                val context = coroutineContext
                OkHttpClient().newCall(Request.Builder().url(server.url("/stalled")).build())
                    .backupResponse { entered.complete(Unit); it.backupToFile(output, 32, context) }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(5000) { entered.await() }
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(output.delete())
        }
    }
}
