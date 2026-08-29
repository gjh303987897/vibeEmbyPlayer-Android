@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.Serializable

/** Strict reader for the Qt `.m3u8sp` POSIX/PAX TAR + leading CBOR index format. */
data class EncryptedHlsTarEntry(
    val path: String,
    val headerOffset: Long,
    val dataOffset: Long,
    val size: Long,
    val sha256: String
)

data class EncryptedHlsTarIndex(
    val containerLength: Long,
    val manifestPath: String,
    val entries: Map<String, EncryptedHlsTarEntry>,
    val serialized: ByteArray,
    val sha256: String
)

object EncryptedHlsTarContainer {
    const val PREFIX_LIMIT = 16 * 1024 * 1024 + 512
    private const val BLOCK = 512L
    private const val MAX_INDEX = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_MEMBER = 8L * 1024 * 1024 * 1024
    private const val MAX_CONTAINER = 64L * 1024 * 1024 * 1024

    @Serializable
    private data class IndexDto(
        val version: Int,
        val containerLength: Long,
        val manifestPath: String,
        val entries: List<EntryDto>
    )

    @Serializable
    private data class EntryDto(
        val path: String,
        val headerOffset: Long,
        val dataOffset: Long,
        val size: Long,
        // Qt writes QByteArray::toHex(), therefore this is a CBOR byte string.
        @ByteString
        val sha256: ByteArray
    )

    /**
     * Android builds before the Qt-compatible writer used the default
     * kotlinx.serialization representation for ByteArray (a CBOR array).
     * Keep a read-only decoder for those archives while the current writer
     * always emits the Qt byte-string representation above.
     */
    @Serializable
    private data class LegacyIndexDto(
        val version: Int,
        val containerLength: Long,
        val manifestPath: String,
        val entries: List<LegacyEntryDto>
    )

    @Serializable
    private data class LegacyEntryDto(
        val path: String,
        val headerOffset: Long,
        val dataOffset: Long,
        val size: Long,
        val sha256: ByteArray
    )

    /** Builds the same deterministic indexed TAR used by the Qt v4 packager. */
    @OptIn(ExperimentalSerializationApi::class)
    fun build(sourceDirectory: File, outputFile: File, manifestPath: String): EncryptedHlsTarIndex {
        require(sourceDirectory.isDirectory && isSafePath(manifestPath))
        val files = sourceDirectory.walkTopDown()
            .filter { it.isFile }
            .map { file -> file to file.relativeTo(sourceDirectory).invariantSeparatorsPath }
            .filter { (_, path) -> isSafePath(path) && path != "index.m3u8" }
            .sortedBy { it.second }
            .toList()
        require(files.isNotEmpty() && files.any { it.second == manifestPath })

        var dto = IndexDto(1, 0, manifestPath, files.map { (file, path) ->
            EntryDto(path, 0, 0, file.length(), TsslCrypto.sha256Hex(file.readBytes()).toByteArray())
        })
        var serialized = ByteArray(0)
        repeat(4) {
            serialized = Cbor.encodeToByteArray(dto)
            require(serialized.size in 1..MAX_INDEX)
            var offset = BLOCK + aligned(serialized.size.toLong())
            val entries = dto.entries.map { entry ->
                offset += paxPrefixSize(entry.path)
                val updated = entry.copy(headerOffset = offset, dataOffset = offset + BLOCK)
                offset += BLOCK + aligned(entry.size)
                updated
            }
            dto = dto.copy(containerLength = offset + BLOCK * 2, entries = entries)
            require(dto.containerLength <= MAX_CONTAINER)
        }
        serialized = Cbor.encodeToByteArray(dto)
        outputFile.parentFile?.mkdirs()
        require(!outputFile.exists()) { "M3U8SP destination already exists" }
        RandomAccessFile(outputFile, "rw").use { output ->
            output.write(tarHeader(".vibe/index.cbor", serialized.size.toLong()))
            output.write(serialized)
            writeZeros(output, aligned(serialized.size.toLong()) - serialized.size)
            dto.entries.forEach { entry ->
                val source = File(sourceDirectory, entry.path)
                if (entry.path.toByteArray().size > 100) {
                    val pax = paxPathRecord(entry.path)
                    output.write(tarHeader(".vibe/pax/${entry.headerOffset}", pax.size.toLong(), 'x'))
                    output.write(pax)
                    writeZeros(output, aligned(pax.size.toLong()) - pax.size)
                }
                val storedPath = if (entry.path.toByteArray().size > 100) ".vibe/file/${entry.headerOffset}" else entry.path
                output.write(tarHeader(storedPath, entry.size))
                source.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                writeZeros(output, aligned(entry.size) - entry.size)
            }
            writeZeros(output, BLOCK * 2)
        }
        require(outputFile.length() == dto.containerLength)
        val prefixSize = minOf(PREFIX_LIMIT.toLong(), outputFile.length()).toInt()
        val prefix = ByteArray(prefixSize)
        outputFile.inputStream().use { input ->
            var read = 0
            while (read < prefix.size) {
                val count = input.read(prefix, read, prefix.size - read)
                require(count >= 0) { "Truncated M3U8SP output" }
                read += count
            }
        }
        return readIndexPrefix(prefix, outputFile.length())
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun readIndexPrefix(prefix: ByteArray, actualContainerLength: Long): EncryptedHlsTarIndex {
        require(prefix.size >= BLOCK && actualContainerLength >= BLOCK * 3) { "Incomplete M3U8SP TAR header" }
        val header = prefix.copyOfRange(0, BLOCK.toInt())
        require(String(header, 257, 5, StandardCharsets.US_ASCII) == "ustar")
        require(cString(header, 0, 100) == ".vibe/index.cbor" && header[156] == '0'.code.toByte())
        require(validChecksum(header)) { "Invalid M3U8SP TAR header" }
        val indexSize = octal(header, 124, 12)
        require(indexSize in 1..MAX_INDEX.toLong() && indexSize <= prefix.size - BLOCK)
        val serialized = prefix.copyOfRange(BLOCK.toInt(), (BLOCK + indexSize).toInt())
        val dto = decodeIndex(serialized)
        require(dto.version == 1 && dto.containerLength == actualContainerLength)
        require(dto.containerLength in (BLOCK * 3)..MAX_CONTAINER)
        require(isSafePath(dto.manifestPath) && dto.manifestPath.endsWith(".m3u8s", true))
        require(dto.entries.size in 1..MAX_ENTRIES)

        val entries = linkedMapOf<String, EncryptedHlsTarEntry>()
        var previousEnd = BLOCK + aligned(indexSize)
        dto.entries.forEach { item ->
            require(isSafePath(item.path) && item.path !in entries)
            require(item.headerOffset >= previousEnd && item.dataOffset == item.headerOffset + BLOCK)
            require(item.size in 0..MAX_MEMBER)
            require(item.dataOffset <= dto.containerLength && item.size <= dto.containerLength - item.dataOffset)
            require(item.dataOffset + aligned(item.size) <= dto.containerLength - BLOCK * 2)
            val digest = item.sha256.toString(StandardCharsets.US_ASCII)
            require(digest.matches(Regex("^[0-9a-fA-F]{64}$")))
            val entry = EncryptedHlsTarEntry(
                item.path, item.headerOffset, item.dataOffset, item.size, digest.lowercase()
            )
            entries[item.path] = entry
            previousEnd = item.dataOffset + aligned(item.size)
        }
        require(dto.manifestPath in entries)
        return EncryptedHlsTarIndex(
            dto.containerLength, dto.manifestPath, entries, serialized,
            TsslCrypto.sha256Hex(serialized)
        )
    }

    fun verifyEntryHeader(header: ByteArray, entry: EncryptedHlsTarEntry) {
        require(header.size == BLOCK.toInt() && header[156] == '0'.code.toByte())
        require(validChecksum(header) && octal(header, 124, 12) == entry.size)
    }

    fun verifyEntry(entry: EncryptedHlsTarEntry, bytes: ByteArray): ByteArray {
        require(bytes.size.toLong() == entry.size && TsslCrypto.sha256Hex(bytes) == entry.sha256) {
            "M3U8SP entry digest mismatch"
        }
        return bytes
    }

    private fun aligned(value: Long): Long = Math.addExact(value, BLOCK - 1) / BLOCK * BLOCK

    @OptIn(ExperimentalSerializationApi::class)
    private fun decodeIndex(serialized: ByteArray): IndexDto =
        runCatching { Cbor.decodeFromByteArray<IndexDto>(serialized) }
            .getOrElse {
                Cbor.decodeFromByteArray<LegacyIndexDto>(serialized).let { legacy ->
                    IndexDto(
                        version = legacy.version,
                        containerLength = legacy.containerLength,
                        manifestPath = legacy.manifestPath,
                        entries = legacy.entries.map { entry ->
                            EntryDto(
                                path = entry.path,
                                headerOffset = entry.headerOffset,
                                dataOffset = entry.dataOffset,
                                size = entry.size,
                                sha256 = entry.sha256
                            )
                        }
                    )
                }
            }

    private fun paxPrefixSize(path: String): Long =
        if (path.toByteArray().size <= 100) 0 else BLOCK + aligned(paxPathRecord(path).size.toLong())

    private fun paxPathRecord(path: String): ByteArray {
        val value = "path=$path\n".toByteArray()
        var length = value.size + 2
        while (true) {
            val actual = length.toString().length + 1 + value.size
            if (actual == length) break
            length = actual
        }
        return "$length ".toByteArray() + value
    }

    private fun tarHeader(path: String, size: Long, type: Char = '0'): ByteArray {
        val encoded = path.toByteArray()
        require(encoded.size <= 100 && size >= 0)
        val header = ByteArray(BLOCK.toInt())
        encoded.copyInto(header)
        "0000644\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        octalField(size, 12).copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        repeat(8) { header[148 + it] = ' '.code.toByte() }
        header[156] = type.code.toByte()
        "ustar\u0000".toByteArray().copyInto(header, 257)
        "00".toByteArray().copyInto(header, 263)
        val checksum = header.sumOf { it.toInt() and 0xff }
        (checksum.toString(8).padStart(6, '0') + "\u0000 ").toByteArray().copyInto(header, 148)
        return header
    }

    private fun octalField(value: Long, size: Int): ByteArray {
        val text = value.toString(8)
        require(text.length + 1 <= size)
        return (text.padStart(size - 1, '0') + '\u0000').toByteArray()
    }

    private fun writeZeros(output: RandomAccessFile, count: Long) {
        var remaining = count
        val zeros = ByteArray(8192)
        while (remaining > 0) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            output.write(zeros, 0, chunk)
            remaining -= chunk
        }
    }

    private fun isSafePath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 &&
        !path.startsWith('/') && !path.contains('\\') &&
        !(path.length >= 2 && path[1] == ':') &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun cString(bytes: ByteArray, offset: Int, size: Int): String {
        val end = (offset until offset + size).firstOrNull { bytes[it] == 0.toByte() } ?: offset + size
        return String(bytes, offset, end - offset, StandardCharsets.UTF_8)
    }

    private fun octal(bytes: ByteArray, offset: Int, size: Int): Long {
        val text = String(bytes, offset, size, StandardCharsets.US_ASCII)
            .trim('\u0000', ' ').takeWhile { it in '0'..'7' }
        require(text.isNotEmpty())
        return text.toLong(8)
    }

    private fun validChecksum(header: ByteArray): Boolean {
        val expected = runCatching { octal(header, 148, 8) }.getOrNull() ?: return false
        val actual = header.indices.sumOf { index ->
            if (index in 148..155) ' '.code else header[index].toInt() and 0xff
        }.toLong()
        return expected == actual
    }
}
