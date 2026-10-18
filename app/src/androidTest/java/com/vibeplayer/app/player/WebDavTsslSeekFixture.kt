package com.vibeplayer.app.player

import android.content.Context
import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.domain.tssl.*
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.player.hls.EncryptedHlsManager
import com.vibeplayer.app.player.hls.EncryptedHlsPlayback
import com.vibeplayer.app.player.hls.EncryptedHlsTarContainer
import com.vibeplayer.app.security.KeystoreSecretStore
import com.vibeplayer.app.test.R
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** A real TSSL v4 container served over strict WebDAV ranges, without user data. */
internal class WebDavTsslSeekFixture(context: Context, testContext: Context) : Closeable {
    private val root = Files.createTempDirectory(context.cacheDir.toPath(), "tssl-seek-").toFile()
    private val secure = SecureSessionStore(context, KeystoreSecretStore(context))
    private val remote = MockWebServer()
    private val config: ServerConfig
    private val manager: EncryptedHlsManager
    val slowRead = CountDownLatch(1)
    private var playback: EncryptedHlsPlayback? = null

    init {
        val source = File(root, "package").apply { mkdirs() }
        ZipInputStream(testContext.resources.openRawResource(R.raw.video_sample_seek)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require('/' !in entry.name && '\\' !in entry.name)
                File(source, entry.name).outputStream().use { zip.copyTo(it) }
            }
        }
        val identifier = TsslCrypto.newIdentifier()
        val nameKey = TsslCrypto.newKey()
        val encryptedName = TsslCrypto.encrypt(nameKey, TsslCrypto.newIv(), "seek-test.mp4".toByteArray(),
            ("vibeEmbyPlayerQT/M3U8S/source-name/v1\n" + identifier).toByteArray())
        val manifestFile = File(source, "index.m3u8")
        val manifest = manifestFile.readText().replace("#EXTM3U\n",
            "#EXTM3U\n#M3U8S-IDENTIFIER:$identifier\n#M3U8S-SOURCE-NAME:${TsslCrypto.toBase64Url(encryptedName)}\n").toByteArray()
        manifestFile.delete()
        File(source, "index.m3u8s").writeBytes(manifest)
        val segments = source.listFiles()!!.filter { it.extension == "ts" }.sortedBy { it.name }.map { file ->
            val key = TsslCrypto.newKey()
            file.writeBytes(TsslCrypto.encrypt(key, TsslCrypto.newIv(), file.readBytes()))
            TsslSegment(file.name, key)
        }
        val container = File(root, "video.m3u8sp")
        val index = EncryptedHlsTarContainer.build(source, container, "index.m3u8s")
        val bytes = container.readBytes()
        val slowEntry = index.entries.getValue("segment_003.ts")
        val store = TsslStore(File(root, "tssl"))
        val document = TsslDocument("TSSL", 4, "AES-256-GCM", identifier,
            TsslCrypto.sha256Hex(manifest), TsslSourceName(encryptedName, nameKey), emptyList(), segments,
            emptyMap(), TsslDocument.V4_CONTAINER_FORMAT, index.sha256, index.containerLength)
        check(runBlocking { store.restore(TsslDocument.toJsonBytes(document)) } is TsslStore.RestoreResult.Stored)
        remote.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")?.removePrefix("bytes=") ?: return MockResponse().setResponseCode(400)
                val start = range.substringBefore('-').toInt()
                val end = range.substringAfter('-').toInt().coerceAtMost(bytes.lastIndex)
                val response = MockResponse().setResponseCode(206).setHeader("ETag", "synthetic-stable")
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
                if (start.toLong() == slowEntry.dataOffset) {
                    slowRead.countDown()
                    response.throttleBody(maxOf(1, (end - start + 1) / 20).toLong(), 400, TimeUnit.MILLISECONDS)
                }
                return response
            }
        }
        remote.start()
        config = ServerConfig(UUID.randomUUID().toString(), "Synthetic DAV", remote.url("/").toString(), "test", ServiceType.WEBDAV)
        check(secure.savePassword(config.id, "synthetic-password"))
        manager = EncryptedHlsManager(context, store, WebDavRepository(WebDavClient(OkHttpClientFactory()), secure))
    }

    fun prepare(): EncryptedHlsPlayback = runBlocking {
        manager.prepareWebDav(config, "video.m3u8sp").getOrThrow().also { playback = it }
    }

    override fun close() {
        playback?.close()
        remote.shutdown()
        secure.clearPassword(config.id)
        check(root.deleteRecursively())
    }
}
