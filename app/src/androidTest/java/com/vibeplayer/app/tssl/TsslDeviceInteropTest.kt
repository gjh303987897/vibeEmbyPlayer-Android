package com.vibeplayer.app.tssl

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vibeplayer.app.domain.tssl.TsslBackupArchive
import com.vibeplayer.app.domain.tssl.TsslBackupService
import com.vibeplayer.app.domain.tssl.TsslBackupTarget
import com.vibeplayer.app.domain.tssl.TsslDocument
import com.vibeplayer.app.domain.tssl.TsslImportWorkflow
import com.vibeplayer.app.domain.tssl.TsslRestoreSummary
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TsslDeviceInteropTest {
    @Test fun qtAndLegacyPackagesRoundTripOnAndroidRuntime() = runBlocking {
        TsslDeviceHarness().use { h ->
            val store = h.store("source")
            h.seed(store)
            val original = store.list().associate { it.fileName to store.exportBytes(it.fileName)!! }
            assertEquals(setOf(2, 3, 4), original.values.map { TsslDocument.parse(it)!!.version }.toSet())
            val files = original.map { (name, bytes) -> h.file(name).apply { writeBytes(bytes) } }
            val archive = h.file("tssl-backup-device-runtime.zip")
            assertEquals(3, TsslBackupArchive.create(files, archive))
            val restored = h.store("restored")
            val workflow = TsslImportWorkflow(restored)
            assertEquals(TsslRestoreSummary(3, 0, 0), workflow.run(listOf(archive), h.file("extract")))
            assertEquals(TsslRestoreSummary(0, 3, 0), workflow.run(listOf(archive), h.file("extract")))
            original.forEach { (name, bytes) -> assertArrayEquals(bytes, restored.exportBytes(name)) }
            ZipFile(h.fixture("tssl-backup-qt-fixture.zip")).use { zip ->
                val name = "41".repeat(32) + ".tssl"
                assertArrayEquals(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }, restored.exportBytes(name))
            }
            h.keepResult(archive, archive.name)
        }
    }

    @Test fun corruptZipImportsNothingButOtherBackupsStillRestore() = runBlocking {
        TsslDeviceHarness().use { h ->
            val store = h.store("restored")
            val summary = TsslImportWorkflow(store).run(listOf(h.fixture("tssl-backup-damaged.zip"),
                h.fixture("legacy-v3.tssl")), h.file("extract"))
            assertEquals(1, summary.restored); assertEquals(0, summary.alreadyExists); assertEquals(1, summary.failed)
            assertEquals(listOf("42".repeat(32) + ".tssl"), store.list().map { it.fileName })
            assertFalse(h.file("extract").exists())
        }
    }

    @Test fun webDavRestoresMixedFormatsAndUploadsAndroidSnapshot() = runBlocking {
        TsslDeviceHarness().use { h -> MockWebServer().use { server ->
            h.enableHttps(server)
            val qt = h.fixture("tssl-backup-qt-fixture.zip").readBytes()
            val legacy = h.fixture("legacy-v3.tssl").readBytes()
            val uploads = LinkedBlockingQueue<ByteArray>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                    "PROPFIND" -> MockResponse().setResponseCode(207).setBody(davListing())
                    "GET" -> when (request.path) {
                        "/dav/backups/tssl-backup-qt-fixture.zip" -> bytes(qt)
                        "/dav/backups/legacy-v3.tssl" -> bytes(legacy)
                        else -> MockResponse().setResponseCode(404)
                    }
                    "PUT" -> { uploads.add(request.body.readByteArray()); MockResponse().setResponseCode(201) }
                    else -> MockResponse().setResponseCode(405)
                }
            }
            server.start()
            val config = ServerConfig(UUID.randomUUID().toString(), "TSSL synthetic DAV", server.url("/dav/").newBuilder().host("localhost").build().toString(), "test", ServiceType.WEBDAV, trustSelfSignedCertificate = true)
            h.password(config)
            val target = TsslBackupTarget.WebDav(config, "backups")
            val store = h.store("dav")
            val service = h.service(store)
            val restored = service.restore(target) as TsslBackupService.RestoreResult.Success
            assertEquals(TsslRestoreSummary(2, 0, 0), restored.summary)
            val duplicate = service.restore(target) as TsslBackupService.RestoreResult.Success
            assertEquals(TsslRestoreSummary(0, 2, 0), duplicate.summary)
            assertEquals(TsslBackupService.BackupResult.Success(2), service.backup(target, store.list().map { it.fileName }))
            val archive = h.file("tssl-backup-device-dav.zip").apply { writeBytes(uploads.poll(2, TimeUnit.SECONDS)!!) }
            assertEquals(2, TsslBackupArchive.extract(archive, h.file("dav-check")).size)
            h.keepResult(archive, archive.name)
        } }
    }

    @Test fun s3PaginatesMixedBackupsAndSignsStreamedSnapshot() = runBlocking {
        TsslDeviceHarness().use { h -> MockWebServer().use { server ->
            val qt = h.fixture("tssl-backup-qt-fixture.zip").readBytes()
            val legacy = h.fixture("legacy-v3.tssl").readBytes()
            val uploads = LinkedBlockingQueue<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "PUT") { uploads.add(request); return MockResponse().setResponseCode(200) }
                    if (request.requestUrl?.queryParameter("list-type") == "2") {
                        val token = request.requestUrl?.queryParameter("continuation-token")
                        return when (token) {
                            null -> MockResponse().setBody("""<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>true</IsTruncated><NextContinuationToken>page&amp;2</NextContinuationToken><Contents><Key>backups/tssl-backup-qt-fixture.zip</Key></Contents></ListBucketResult>""")
                            "page&2" -> MockResponse().setBody("""<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>backups/legacy-v3.tssl</Key></Contents><Contents><Key>backups/unrelated.zip</Key></Contents></ListBucketResult>""")
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    return when (request.path) {
                        "/device-test-bucket/backups/tssl-backup-qt-fixture.zip" -> bytes(qt)
                        "/device-test-bucket/backups/legacy-v3.tssl" -> bytes(legacy)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            val target = TsslBackupTarget.S3(server.url("/").toString(), "device-test-bucket", "us-east-1", "backups",
                "SyntheticAccessForTest", "synthetic-secret-for-test")
            val source = h.store("source"); h.seed(source)
            assertEquals(TsslBackupService.BackupResult.Success(3), h.service(source).backup(target, source.list().map { it.fileName }))
            val put = uploads.poll(2, TimeUnit.SECONDS)!!
            val payload = put.body.readByteArray()
            assertTrue(put.getHeader("Authorization")!!.startsWith("AWS4-HMAC-SHA256 "))
            assertEquals(TsslBackupArchive.sha256(payload), put.getHeader("x-amz-content-sha256"))
            val archive = h.file("tssl-backup-device-s3.zip").apply { writeBytes(payload) }
            assertEquals(3, TsslBackupArchive.extract(archive, h.file("s3-check")).size)
            val store = h.store("s3-restored")
            val restored = h.service(store).restore(target) as TsslBackupService.RestoreResult.Success
            assertEquals(TsslRestoreSummary(2, 0, 0), restored.summary)
            h.keepResult(archive, archive.name)
        } }
    }

    @Test fun cancellingStalledWebDavDownloadReleasesTemporaryFiles() = runBlocking {
        TsslDeviceHarness().use { h -> MockWebServer().use { server ->
            h.enableHttps(server)
            server.enqueue(MockResponse().setResponseCode(207).setBody(davListing(includeLegacy = false)))
            server.enqueue(bytes(h.fixture("tssl-backup-qt-fixture.zip").readBytes()).setBodyDelay(2, TimeUnit.SECONDS))
            server.start()
            val config = ServerConfig(UUID.randomUUID().toString(), "TSSL cancellation", server.url("/dav/").newBuilder().host("localhost").build().toString(), "test", ServiceType.WEBDAV, trustSelfSignedCertificate = true)
            h.password(config)
            val job = async(Dispatchers.IO) { h.service(h.store("cancel")).restore(TsslBackupTarget.WebDav(config, "backups")) }
            assertEquals("PROPFIND", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            assertEquals("GET", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
            delay(100)
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(h.file("cache").listFiles().orEmpty().isEmpty())
            assertTrue(h.store("cancel").list().isEmpty())
        } }
    }

    private fun bytes(value: ByteArray) = MockResponse().setBody(Buffer().write(value))

    /** Display names deliberately differ from resource names: GET must use the href. */
    private fun davListing(includeLegacy: Boolean = true): String = """<?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response><D:href>/dav/backups/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
          <D:response><D:href>/dav/backups/tssl-backup-qt-fixture.zip</D:href><D:propstat><D:prop><D:displayname>Qt 压缩备份</D:displayname></D:prop></D:propstat></D:response>
          ${if (includeLegacy) "<D:response><D:href>/dav/backups/legacy-v3.tssl</D:href><D:propstat><D:prop><D:displayname>旧密钥包</D:displayname></D:prop></D:propstat></D:response>" else ""}
          <D:response><D:href>/dav/backups/unrelated.zip</D:href></D:response>
        </D:multistatus>""".trimIndent()
}
