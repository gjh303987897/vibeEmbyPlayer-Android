package com.vibeplayer.app.tssl

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.domain.tssl.TsslBackupService
import com.vibeplayer.app.domain.tssl.TsslImportWorkflow
import com.vibeplayer.app.domain.tssl.TsslLocalBackup
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.security.KeystoreSecretStore
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals

/** Uses only synthetic fixtures and private, per-test storage. Never resets the installed app. */
internal class TsslDeviceHarness : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app = instrumentation.targetContext
    val root = Files.createTempDirectory(app.cacheDir.toPath(), "tssl-device-").toFile()
    private val factory = OkHttpClientFactory()
    private val secure = SecureSessionStore(app, KeystoreSecretStore(app))
    private val credentials = mutableSetOf<String>()
    private val repository = WebDavRepository(WebDavClient(factory), secure)
    val local = TsslLocalBackup(app)
    private val worker = object : ContextWrapper(app) {
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    fun store(name: String) = TsslStore(File(root, name))
    fun service(store: TsslStore) = TsslBackupService(store, repository, factory, local, TsslImportWorkflow(store), worker)
    fun file(name: String) = File(root, name)
    fun fixture(name: String): File = file(name).also { file ->
        instrumentation.context.assets.open("tssl/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    suspend fun seed(store: TsslStore) {
        val summary = TsslImportWorkflow(store).run(listOf(fixture("tssl-backup-qt-fixture.zip"),
            fixture("legacy-v3.tssl"), fixture("legacy-v4.tssl")), file("seed-${UUID.randomUUID()}"))
        assertEquals(3, summary.restored)
        assertEquals(0, summary.failed)
    }

    fun password(server: ServerConfig) {
        credentials += server.id
        check(secure.savePassword(server.id, "synthetic-device-password"))
    }

    fun enableHttps(server: okhttp3.mockwebserver.MockWebServer) {
        val password = "fixture-only".toCharArray()
        // Import DER directly: Android's PKCS12 provider cannot read the JDK fixture's MAC.
        val privateKey = instrumentation.context.assets.open("tls/localhost-key.pk8").use {
            java.security.KeyFactory.getInstance("RSA").generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(it.readBytes()),
            )
        }
        val certificate = instrumentation.context.assets.open("tls/localhost-cert.der").use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("localhost", privateKey, password, arrayOf(certificate))
        }
        val managers = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, password) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.useHttps(tls.socketFactory, false)
    }

    fun keepResult(source: File, name: String) {
        val output = File(app.getExternalFilesDir(null), "tssl-device-results").apply { mkdirs() }
        source.copyTo(File(output, name), overwrite = true)
        println("TSSL synthetic result: ${File(output, name).absolutePath}")
    }

    override fun close() {
        credentials.forEach(secure::clearPassword)
        check(root.deleteRecursively()) { "Device test left private temporary files" }
    }
}
