package com.vibeplayer.app.domain.tssl

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TsslBackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val first = backupPackage()
    private val second = backupPackage("22".repeat(32), 4)
    private fun file(name: String) = File(temporary.root, name)

    @Test fun `reads genuine Qt miniz snapshot preserving exact bytes`() {
        val archive = file("qt.zip")
        javaClass.getResourceAsStream("/tssl/tssl-backup-qt-fixture.zip")!!.use { input -> archive.outputStream().use(input::copyTo) }
        val files = TsslBackupArchive.extract(archive, file("out"))
        assertEquals(1, files.size)
        assertEquals("41".repeat(32) + ".tssl", files.single().name)
        assertEquals(2, TsslDocument.parse(files.single().readBytes())!!.version)
        ZipFile(archive).use { zip ->
            assertArrayEquals(zip.getInputStream(zip.getEntry(files.single().name)).use { it.readBytes() }, files.single().readBytes())
        }
    }

    @Test fun `writer round trips v2 v3 v4 without reserializing documents`() {
        val bytes = listOf(first, backupPackage("33".repeat(32), 3), second)
        val inputs = bytes.mapIndexed { index, value -> file("input-$index.tssl").apply { writeBytes(value) } }
        val zip = file(TsslBackupArchive.newFileName())
        assertEquals(3, TsslBackupArchive.create(inputs, zip))
        val restored = TsslBackupArchive.extract(zip, file("out"))
        bytes.zip(restored).forEach { (original, restoredFile) -> assertArrayEquals(original, restoredFile.readBytes()) }
        ZipFile(zip).use { reader ->
            val json = JSONObject(reader.getInputStream(reader.getEntry("manifest.json")).bufferedReader().use { it.readText() })
            assertEquals("TSSL-BACKUP", json.getString("format")); assertEquals(1, json.getInt("version"))
            assertEquals(3, json.getInt("count")); assertTrue(json.has("createdAt"))
        }
    }

    @Test fun `accepts stored ZIP and deflate with data descriptors`() {
        listOf(false, true).forEach { stored ->
            val zip = backupZip(file("$stored.zip"), backupEntries(first), stored)
            assertArrayEquals(first, TsslBackupArchive.extract(zip, file("out-$stored")).single().readBytes())
        }
    }

    @Test fun `discovers only Qt backup ZIP names and safe loose TSSL names`() {
        listOf("tssl-backup-abc-123.zip", "TSSL-BACKUP-ABC.ZIP", TsslBackupArchive.newFileName(), "old-name.tssl").forEach {
            assertTrue(it, TsslBackupArchive.isBackupName(it))
        }
        listOf("photos.zip", "tssl-backup-.zip", "../old.tssl", "dir\\old.tssl", "old\n.tssl").forEach {
            assertFalse(it, TsslBackupArchive.isBackupName(it))
        }
    }

    @Test fun `rejects traversal directories unexpected files and duplicate ZIP entries`() {
        listOf("../escape.tssl", "folder/${"11".repeat(32)}.tssl", "folder/", "/absolute.tssl", "readme.txt").forEachIndexed { index, name ->
            reject(backupZip(file("bad-$index.zip"), backupEntries(first) + (name to byteArrayOf(1))))
        }
        val zip = backupZip(file("duplicate.zip"), backupEntries(first, second), true)
        val bytes = zip.readBytes()
        val dirs = bytes.zipDirectory()
        val duplicateName = bytes.copyOfRange(dirs[0] + 46, dirs[0] + 46 + 69)
        duplicateName.copyInto(bytes, dirs[1] + 46)
        duplicateName.copyInto(bytes, bytes.zipU32(dirs[1] + 42).toInt() + 30)
        zip.writeBytes(bytes)
        reject(zip)
    }

    @Test fun `rejects encrypted special files unsupported methods and inconsistent headers`() {
        val mutations: List<(ByteArray, Int, Int) -> Unit> = listOf(
            { b, c, l -> b.zipSet(c + 8, 1, 2); b.zipSet(l + 6, 1, 2) },
            { b, c, _ -> b.zipSet(c + 38, 0xa000L shl 16) }, // Unix symbolic link
            { b, c, _ -> b.zipSet(c + 38, 0x4000L shl 16) }, // directory
            { b, c, _ -> b.zipSet(c + 38, 0x10) },
            { b, c, l -> b.zipSet(c + 10, 12, 2); b.zipSet(l + 8, 12, 2) },
            { b, _, l -> b[l + 30] = '0'.code.toByte() },
            { b, c, _ -> b.zipSet(c + 42, 0xffffffffL) },
            { b, c, _ -> b.zipSet(c + 34, 1, 2) }
        )
        mutations.forEachIndexed { index, mutation ->
            val zip = backupZip(file("type-$index.zip"), backupEntries(first), true)
            val bytes = zip.readBytes(); val central = bytes.zipDirectory().first()
            mutation(bytes, central, bytes.zipU32(central + 42).toInt()); zip.writeBytes(bytes)
            reject(zip)
        }
    }

    @Test fun `bounds central directory and expanded sizes before allocating or extracting`() {
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { it.zipSet(it.size - 22 + 12, 8L * 1024 * 1024 + 1) },
            { it.zipSet(it.size - 22 + 10, 10002, 2) },
            { it.zipSet(it.size - 22 + 10, 65535, 2) },
            { it.zipSet(it.size - 22 + 6, 1, 2) },
            { it.zipSet(it.zipDirectory().first() + 24, TsslBackupArchive.MAX_PACKAGE_BYTES + 1) },
            { it.zipSet(it.zipDirectory().last() + 24, 4L * 1024 * 1024 + 1) }
        )
        mutations.forEachIndexed { index, mutation ->
            val zip = backupZip(file("limit-$index.zip"), backupEntries(first), true)
            val bytes = zip.readBytes(); mutation(bytes); zip.writeBytes(bytes); reject(zip)
        }
        val packages = (1..33).map { backupPackage("%064x".format(it)) }
        val zip = backupZip(file("expanded.zip"), backupEntries(*packages.toTypedArray()), true)
        val bytes = zip.readBytes()
        bytes.zipDirectory().dropLast(1).forEach { bytes.zipSet(it + 24, TsslBackupArchive.MAX_PACKAGE_BYTES) }
        zip.writeBytes(bytes); reject(zip)
    }

    @Test fun `checks CRC even when manifest SHA and contents are correct`() {
        val zip = backupZip(file("crc.zip"), backupEntries(first), true)
        val bytes = zip.readBytes(); val central = bytes.zipDirectory().first(); val local = bytes.zipU32(central + 42).toInt()
        val wrong = bytes.zipU32(central + 16) xor 1
        bytes.zipSet(central + 16, wrong); bytes.zipSet(local + 14, wrong)
        zip.writeBytes(bytes); reject(zip)
    }

    @Test fun `validates all hashes counts types roots and documents before publishing`() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("format", "other") }, { it.put("version", 2) }, { it.put("version", "1") },
            { it.put("count", 1) }, { it.put("count", "2") },
            { it.getJSONArray("packages").getJSONObject(1).put("sha256", "00".repeat(32)) },
            { it.getJSONArray("packages").getJSONObject(1).put("rootManifestSha256", "33".repeat(32)) },
            { it.getJSONArray("packages").getJSONObject(1).put("size", second.size + 1) },
            { it.getJSONArray("packages").getJSONObject(1).put("size", second.size.toString()) }
        )
        mutations.forEachIndexed { index, mutation ->
            val entries = backupEntries(first, second).toMutableList()
            val manifest = JSONObject(String(entries.last().second)); mutation(manifest)
            entries[entries.lastIndex] = "manifest.json" to manifest.toString().toByteArray()
            reject(backupZip(file("manifest-$index.zip"), entries))
        }
        val entries = backupEntries(first).toMutableList()
        val invalid = "{}".toByteArray()
        val manifest = JSONObject(String(entries.last().second))
        manifest.getJSONArray("packages").getJSONObject(0).put("sha256", TsslBackupArchive.sha256(invalid)).put("size", invalid.size)
        entries[0] = entries[0].first to invalid
        entries[1] = "manifest.json" to manifest.toString().toByteArray()
        reject(backupZip(file("invalid-tssl.zip"), entries))
        val differentRoot = backupEntries(first).toMutableList()
        differentRoot[0] = differentRoot[0].first to backupPackage("77".repeat(32))
        val rootManifest = JSONObject(String(differentRoot.last().second))
        rootManifest.getJSONArray("packages").getJSONObject(0).put("sha256", TsslBackupArchive.sha256(differentRoot[0].second))
        differentRoot[1] = "manifest.json" to rootManifest.toString().toByteArray()
        reject(backupZip(file("root-mismatch.zip"), differentRoot))
    }

    @Test fun `failed publication preserves existing files and removes new partial output`() {
        val zip = backupZip(file("existing.zip"), backupEntries(first, second))
        val output = file("out").apply { mkdirs() }
        val existing = File(output, "22".repeat(32) + ".tssl").apply { writeText("keep existing") }
        assertThrows(Exception::class.java) { TsslBackupArchive.extract(zip, output) }
        assertEquals(listOf(existing.name), output.list()!!.toList())
        assertEquals("keep existing", existing.readText())
    }

    @Test fun `cancellation rolls back publication and closes temporary ZIP output`() {
        val zip = backupZip(file("cancel.zip"), backupEntries(first, second))
        val output = file("out")
        var publishing = false; var checks = 0
        assertThrows(CancellationException::class.java) {
            TsslBackupArchive.extract(zip, output, check = {
                if (publishing && ++checks == 2) throw CancellationException("test cancellation")
            }, progress = { done, total -> if (done == total) publishing = true })
        }
        assertTrue(output.listFiles()!!.isEmpty())
        val input = file("input.tssl").apply { writeBytes(first) }; val destination = file("canceled.zip")
        var canceled = false
        assertThrows(CancellationException::class.java) {
            TsslBackupArchive.create(listOf(input), destination, check = { if (canceled) throw CancellationException() },
                progress = { done, _ -> if (done == 1) canceled = true })
        }
        assertFalse(destination.exists())
        assertFalse(temporary.root.list()!!.any { it.startsWith(".tssl-backup-") })
    }

    @Test fun `writer rejects duplicate or invalid packages and leaves existing archive intact`() {
        val input = file("input.tssl").apply { writeBytes(first) }
        val output = file("out.zip")
        assertThrows(Exception::class.java) { TsslBackupArchive.create(listOf(input, input), output) }
        assertFalse(output.exists())
        input.writeText("{}"); assertThrows(Exception::class.java) { TsslBackupArchive.create(listOf(input), output) }
        output.writeText("original"); assertThrows(Exception::class.java) { TsslBackupArchive.create(listOf(input), output) }
        assertEquals("original", output.readText())
    }

    private fun reject(zip: File) {
        val output = file("rejected-${zip.name}")
        assertThrows(zip.name, Exception::class.java) { TsslBackupArchive.extract(zip, output) }
        assertTrue(zip.name, !output.exists() || output.listFiles()!!.isEmpty())
    }
}
