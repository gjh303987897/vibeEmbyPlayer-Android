package com.vibeplayer.app.domain.tssl

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic keys only; no production media or credentials. */
internal fun backupPackage(root: String = "11".repeat(32), version: Int = 2, note: String = "original bytes"): ByteArray {
    val key = ByteArray(32) { (it + 1).toByte() }
    val identifier = "A".repeat(TsslDocument.IDENTIFIER_LENGTH)
    val sourceName = if (version >= 3) TsslSourceName(
        TsslCrypto.encrypt(key, ByteArray(TsslCrypto.IV_BYTES) { it.toByte() }, "sample.mkv".toByteArray(),
            ("vibeEmbyPlayerQT/M3U8S/source-name/v1\n" + identifier).toByteArray()), key) else null
    val document = TsslDocument(TsslDocument.FORMAT, version, TsslDocument.ALGORITHM, identifier, root,
        sourceName, emptyList(), listOf(TsslSegment("segment_000001.ts", key)), emptyMap(),
        containerFormat = if (version == 4) TsslDocument.V4_CONTAINER_FORMAT else null,
        containerIndexSha256 = if (version == 4) "99".repeat(32) else null,
        containerLength = if (version == 4) 4096L else null)
    return (JSONObject(String(TsslDocument.toJsonBytes(document))).put("fixtureNote", note).toString(2) + "\n").toByteArray()
}

internal fun backupEntries(vararg packages: ByteArray): List<Pair<String, ByteArray>> {
    val entries = packages.map { bytes -> "${TsslDocument.parse(bytes)!!.rootManifestSha256}.tssl" to bytes }
    val list = JSONArray()
    entries.forEach { (name, bytes) -> list.put(JSONObject().put("file", name)
        .put("rootManifestSha256", name.removeSuffix(".tssl"))
        .put("sha256", TsslBackupArchive.sha256(bytes)).put("size", bytes.size)) }
    val manifest = JSONObject().put("format", "TSSL-BACKUP").put("version", 1).put("count", packages.size).put("packages", list)
    return entries + ("manifest.json" to manifest.toString().toByteArray())
}

internal fun backupZip(file: File, entries: List<Pair<String, ByteArray>>, stored: Boolean = false): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (name, bytes) ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry()
        }
    }
    return file
}

internal fun ByteArray.zipU16(offset: Int): Int = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
internal fun ByteArray.zipU32(offset: Int): Long = zipU16(offset).toLong() or (zipU16(offset + 2).toLong() shl 16)
internal fun ByteArray.zipSet(offset: Int, value: Long, length: Int = 4) { repeat(length) { this[offset + it] = (value ushr (it * 8)).toByte() } }
internal fun ByteArray.zipDirectory(): List<Int> {
    var offset = zipU32(size - 22 + 16).toInt()
    return List(zipU16(size - 22 + 10)) {
        val start = offset
        offset += 46 + zipU16(start + 28) + zipU16(start + 30) + zipU16(start + 32)
        start
    }
}
