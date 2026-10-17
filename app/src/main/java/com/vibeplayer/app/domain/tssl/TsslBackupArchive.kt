package com.vibeplayer.app.domain.tssl

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

enum class TsslBackupFormat { ZIP, FILES }
enum class TsslBackupStage { SCANNING, COMPRESSING, UPLOADING, LISTING, DOWNLOADING, EXTRACTING, IMPORTING }

/** Qt TSSL-BACKUP v1. File-only operations; callers supply private staging space and run on IO. */
object TsslBackupArchive {
    const val MAX_PACKAGE_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_EXPANDED_BYTES = MAX_ARCHIVE_BYTES
    const val MAX_PACKAGES = 10_000
    private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    private const val MAX_DIRECTORY_BYTES = 8L * 1024 * 1024
    private const val MANIFEST = "manifest.json"
    private val digestName = Regex("[0-9a-f]{64}\\.tssl")
    private val digest = Regex("[0-9a-f]{64}")
    private val archiveName = Regex("tssl-backup-[A-Za-z0-9_-]+\\.zip", RegexOption.IGNORE_CASE)
    private val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC)

    fun isArchiveName(name: String) = archiveName.matches(name)
    fun isBackupName(name: String) = isArchiveName(name) || (name.length in 6..255 && name.endsWith(".tssl", true) &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 })
    fun newFileName() = "tssl-backup-${timestamp.format(Instant.now())}-${UUID.randomUUID()}.zip"

    fun create(files: List<File>, destination: File, check: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        check()
        require(files.size in 1..MAX_PACKAGES) { "Backup must contain between 1 and 10000 TSSL packages" }
        require(!destination.exists()) { "Backup archive already exists" }
        val temporary = File.createTempFile(".tssl-backup-", ".tmp", destination.absoluteFile.parentFile)
        try {
            val entries = JSONArray()
            val names = mutableSetOf<String>()
            var expanded = 0L
            progress(0, files.size)
            // Own the file separately: ZipOutputStream.close can itself fail on cancellation.
            temporary.outputStream().buffered().use { output ->
                ZipOutputStream(LimitedOutput(output, MAX_ARCHIVE_BYTES, check)).use { zip ->
                    zip.setLevel(3)
                    files.forEachIndexed { index, file ->
                        check()
                        val bytes = readPackage(file, check)
                        val doc = TsslDocument.parse(bytes) ?: throw IOException("Invalid TSSL package")
                        val root = doc.rootManifestSha256.lowercase()
                        val name = "$root.tssl"
                        require(names.add(name)) { "Backup contains duplicate TSSL root digests" }
                        expanded += bytes.size
                        require(expanded <= MAX_EXPANDED_BYTES) { "Expanded backup exceeds 2 GiB" }
                        zip.putNextEntry(ZipEntry(name))
                        bytes.inputStream().use { copyBounded(it, zip, MAX_PACKAGE_BYTES, check) }
                        zip.closeEntry()
                        entries.put(JSONObject().put("file", name).put("rootManifestSha256", root)
                            .put("sha256", sha256(bytes)).put("size", bytes.size))
                        progress(index + 1, files.size)
                    }
                    val manifest = JSONObject().put("format", "TSSL-BACKUP").put("version", 1)
                        .put("count", files.size).put("createdAt", Instant.now().toString()).put("packages", entries)
                        .toString().toByteArray(Charsets.UTF_8)
                    require(manifest.size <= MAX_MANIFEST_BYTES && expanded + manifest.size <= MAX_EXPANDED_BYTES) { "Backup manifest exceeds limits" }
                    zip.putNextEntry(ZipEntry(MANIFEST))
                    zip.write(manifest)
                    zip.closeEntry()
                }
            }
            check()
            Files.move(temporary.toPath(), destination.toPath())
            return files.size
        } finally { temporary.delete() }
    }

    /** The entire archive is checked before any package is returned/published. Existing files survive failure. */
    fun extract(archive: File, destination: File, check: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        check()
        val metadata = inspectDirectory(archive, check)
        require(!Files.isSymbolicLink(destination.toPath()) && (destination.isDirectory || destination.mkdirs())) { "Invalid restore directory" }
        val staging = Files.createTempDirectory(destination.toPath(), ".tssl-restore-").toFile()
        val published = mutableListOf<File>()
        try {
            ZipFile(archive).use { zip ->
                val manifestEntry = metadata.singleOrNull { it.name == MANIFEST } ?: throw IOException("Backup manifest missing")
                val manifestBytes = readEntry(zip, manifestEntry, check)
                val expected = parseManifest(manifestBytes, metadata.size - 1)
                progress(0, expected.size)
                metadata.filter { it.name != MANIFEST }.forEachIndexed { index, entry ->
                    check()
                    val info = expected[entry.name] ?: throw IOException("Unexpected backup entry")
                    require(info.size == entry.size) { "Backup package size mismatch" }
                    val file = File(staging, entry.name)
                    val crc = CRC32()
                    val hash = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(zip.getEntry(entry.name) ?: throw IOException("Missing ZIP entry")).use { input ->
                        file.outputStream().buffered().use { output ->
                            copyBounded(input, output, entry.size, check) { bytes, length -> crc.update(bytes, 0, length); hash.update(bytes, 0, length) }
                        }
                    }
                    require(file.length() == entry.size && crc.value == entry.crc && hex(hash.digest()) == info.hash) { "Backup package checksum mismatch" }
                    val doc = TsslDocument.parse(readPackage(file, check)) ?: throw IOException("Invalid TSSL package in backup")
                    require(doc.rootManifestSha256.lowercase() == info.root) { "Backup root digest mismatch" }
                    progress(index + 1, expected.size)
                }
            }
            for (entry in metadata.filter { it.name != MANIFEST }) {
                check()
                val target = File(destination, entry.name)
                Files.move(File(staging, entry.name).toPath(), target.toPath())
                published += target
            }
            check()
            return published.toList()
        } catch (error: Throwable) {
            published.forEach { it.delete() }
            throw error
        } finally { staging.deleteRecursively() }
    }

    fun readPackage(file: File, check: () -> Unit = {}): ByteArray {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..MAX_PACKAGE_BYTES) { "TSSL package is empty or exceeds 64 MiB" }
        return file.inputStream().use { readBounded(it, MAX_PACKAGE_BYTES, check) }
    }

    fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun sha256(file: File, check: () -> Unit = {}): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> copyBounded(input, null, MAX_ARCHIVE_BYTES, check) { bytes, length -> hash.update(bytes, 0, length) } }
        return hex(hash.digest())
    }

    fun copyBounded(input: InputStream, output: OutputStream?, maximum: Long, check: () -> Unit = {},
        onBytes: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            check()
            val read = input.read(buffer)
            if (read < 0) break
            count += read
            require(count <= maximum) { "Backup exceeds size limit" }
            output?.write(buffer, 0, read)
            onBytes(buffer, read)
        }
        check()
        return count
    }

    fun readBounded(input: InputStream, maximum: Long, check: () -> Unit = {}): ByteArray =
        java.io.ByteArrayOutputStream().use { output -> copyBounded(input, output, maximum, check); output.toByteArray() }

    private fun readEntry(zip: ZipFile, entry: Entry, check: () -> Unit): ByteArray {
        val crc = CRC32()
        val bytes = zip.getInputStream(zip.getEntry(entry.name) ?: throw IOException("Missing ZIP entry")).use {
            readBounded(it, entry.size, check)
        }
        crc.update(bytes)
        require(bytes.size.toLong() == entry.size && crc.value == entry.crc) { "Backup manifest checksum mismatch" }
        return bytes
    }

    private data class PackageInfo(val root: String, val hash: String, val size: Long)
    private fun parseManifest(bytes: ByteArray, count: Int): Map<String, PackageInfo> {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        fun number(obj: JSONObject, key: String): Long {
            val value = obj.get(key) as? Number ?: throw IOException("Invalid backup number")
            val n = value.toDouble()
            require(n.isFinite() && n == value.toLong().toDouble()) { "Non-integral backup number" }
            return value.toLong()
        }
        require(json.opt("format") == "TSSL-BACKUP" && number(json, "version") == 1L && number(json, "count") == count.toLong()) { "Unsupported backup manifest or count mismatch" }
        val list = json.getJSONArray("packages")
        require(list.length() == count) { "Backup package count mismatch" }
        val result = linkedMapOf<String, PackageInfo>()
        repeat(list.length()) { index ->
            val item = list.getJSONObject(index)
            val name = item.opt("file") as? String ?: throw IOException("Invalid package filename")
            val root = item.opt("rootManifestSha256") as? String ?: throw IOException("Invalid root digest")
            val hash = item.opt("sha256") as? String ?: throw IOException("Invalid package hash")
            val size = number(item, "size")
            require(digestName.matches(name) && digest.matches(root) && digest.matches(hash) && name == "$root.tssl" && size in 1..MAX_PACKAGE_BYTES && name !in result) { "Invalid or duplicate backup package" }
            result[name] = PackageInfo(root, hash, size)
        }
        return result
    }

    private data class Entry(val name: String, val flags: Int, val method: Int, val crc: Long,
        val compressed: Long, val size: Long, val offset: Long, var end: Long = 0)

    /** Bound metadata BEFORE ZipFile allocates its central directory; validate attributes absent from ZipEntry. */
    private fun inspectDirectory(file: File, check: () -> Unit): List<Entry> = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        require(length in 22..MAX_ARCHIVE_BYTES) { "Backup ZIP is empty or exceeds 2 GiB" }
        val tail = ByteArray(minOf(length, 65_557).toInt())
        input.seek(length - tail.size); input.readFully(tail)
        val end = (tail.size - 22 downTo 0).firstOrNull { tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tail.size }
            ?: throw IOException("ZIP directory missing")
        val count = tail.u16(end + 10)
        val directorySize = tail.u32(end + 12)
        val directoryOffset = tail.u32(end + 16)
        require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0 && count == tail.u16(end + 8) &&
            count in 2..MAX_PACKAGES + 1 && directorySize <= MAX_DIRECTORY_BYTES &&
            directoryOffset + directorySize == length - tail.size + end) { "ZIP64, multi-disk or oversized ZIP directory" }
        input.seek(directoryOffset)
        val metadata = ByteArray(directorySize.toInt()).also(input::readFully)
        var position = 0
        var expanded = 0L
        val names = mutableSetOf<String>()
        val entries = mutableListOf<Entry>()
        repeat(count) {
            check()
            require(position + 46 <= metadata.size && metadata.u32(position) == 0x02014b50L) { "Corrupt ZIP directory" }
            val flags = metadata.u16(position + 8)
            val method = metadata.u16(position + 10)
            val crc = metadata.u32(position + 16)
            val compressed = metadata.u32(position + 20)
            val size = metadata.u32(position + 24)
            val nameLength = metadata.u16(position + 28)
            val extraLength = metadata.u16(position + 30)
            val commentLength = metadata.u16(position + 32)
            val attributes = metadata.u32(position + 38)
            val offset = metadata.u32(position + 42)
            val next = position + 46 + nameLength + extraLength + commentLength
            require(next <= metadata.size && metadata.u16(position + 34) == 0 && flags and 0x080e.inv() == 0 && method in listOf(0, 8) &&
                ((attributes ushr 16).toInt() and 0xf000) in listOf(0, 0x8000) && attributes and 0x10 == 0L) { "Unsupported ZIP entry or file type" }
            val name = String(metadata, position + 46, nameLength, Charsets.US_ASCII)
            require(name.toByteArray(Charsets.US_ASCII).contentEquals(metadata.copyOfRange(position + 46, position + 46 + nameLength)) &&
                (name == MANIFEST || digestName.matches(name)) && names.add(name) &&
                size in 1L..(if (name == MANIFEST) MAX_MANIFEST_BYTES else MAX_PACKAGE_BYTES) && compressed < 0xffffffffL) { "Unsafe, duplicate or oversized ZIP entry" }
            checkExtra(metadata.copyOfRange(position + 46 + nameLength, position + 46 + nameLength + extraLength))
            expanded += size
            require(expanded <= MAX_EXPANDED_BYTES) { "Expanded backup exceeds 2 GiB" }
            entries += Entry(name, flags, method, crc, compressed, size, offset)
            position = next
        }
        require(position == metadata.size && MANIFEST in names) { "ZIP contents or manifest mismatch" }
        for (entry in entries) {
            check()
            require(entry.offset + 30 <= directoryOffset) { "Invalid ZIP local header" }
            input.seek(entry.offset)
            val header = ByteArray(30).also(input::readFully)
            val nameLength = header.u16(26)
            val extraLength = header.u16(28)
            val dataStart = entry.offset + 30 + nameLength + extraLength
            require(header.u32(0) == 0x04034b50L && header.u16(6) == entry.flags && header.u16(8) == entry.method &&
                dataStart + entry.compressed <= directoryOffset) { "ZIP local/central header mismatch" }
            val name = ByteArray(nameLength).also(input::readFully)
            require(name.contentEquals(entry.name.toByteArray(Charsets.US_ASCII))) { "ZIP filename mismatch" }
            checkExtra(ByteArray(extraLength).also(input::readFully))
            if (entry.flags and 8 == 0) require(header.u32(14) == entry.crc && header.u32(18) == entry.compressed && header.u32(22) == entry.size) { "ZIP size or CRC header mismatch" }
            else require((header.u32(14) == 0L || header.u32(14) == entry.crc) &&
                (header.u32(18) == 0L || header.u32(18) == entry.compressed) &&
                (header.u32(22) == 0L || header.u32(22) == entry.size)) { "ZIP descriptor header mismatch" }
            entry.end = dataStart + entry.compressed
            if (entry.flags and 8 != 0) {
                require(entry.end + 12 <= directoryOffset) { "ZIP descriptor missing" }
                input.seek(entry.end)
                var value = input.readU32()
                if (value == 0x08074b50L) { entry.end += 4; value = input.readU32() }
                require(entry.end + 12 <= directoryOffset && value == entry.crc && input.readU32() == entry.compressed && input.readU32() == entry.size) { "ZIP descriptor mismatch" }
                entry.end += 12
            }
        }
        entries.sortedBy { it.offset }.zipWithNext().forEach { (a, b) -> require(a.end <= b.offset) { "Overlapping ZIP entries" } }
        entries
    }

    private fun checkExtra(bytes: ByteArray) {
        var pos = 0
        while (pos < bytes.size) {
            require(pos + 4 <= bytes.size) { "Corrupt ZIP extra field" }
            val id = bytes.u16(pos)
            val size = bytes.u16(pos + 2)
            require(id != 1 && pos + 4 + size <= bytes.size) { "ZIP64 or corrupt extra field" }
            pos += 4 + size
        }
    }
    private fun ByteArray.u16(i: Int): Int = (this[i].toInt() and 255) or ((this[i + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(i: Int): Long = u16(i).toLong() or (u16(i + 2).toLong() shl 16)
    private fun RandomAccessFile.readU32() = ByteArray(4).also(::readFully).u32(0)
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private class LimitedOutput(private val output: OutputStream, private val maximum: Long, private val check: () -> Unit) : OutputStream() {
        private var count = 0L
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(); count += length; require(count <= maximum) { "Compressed backup exceeds 2 GiB" }
            output.write(bytes, offset, length)
        }
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
}
