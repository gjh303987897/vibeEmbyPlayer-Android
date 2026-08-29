@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncryptedHlsTarContainerTest {
    @Test
    fun `writer creates a self-consistent Qt compatible container`() {
        val root = Files.createTempDirectory("m3u8sp-source").toFile()
        val output = File(root.parentFile, "${root.name}.m3u8sp")
        try {
            val manifest = "#EXTM3U\nsegment_000001.ts\n".toByteArray()
            val segment = "encrypted-segment".toByteArray()
            File(root, "index.m3u8s").writeBytes(manifest)
            File(root, "segment_000001.ts").writeBytes(segment)
            val built = EncryptedHlsTarContainer.build(root, output, "index.m3u8s")
            assertTrue(output.isFile)
            assertEquals(output.length(), built.containerLength)

            java.io.RandomAccessFile(output, "r").use { archive ->
                built.entries.forEach { (path, entry) ->
                    archive.seek(entry.headerOffset)
                    val header = ByteArray(512).also { archive.readFully(it) }
                    EncryptedHlsTarContainer.verifyEntryHeader(header, entry)
                    archive.seek(entry.dataOffset)
                    val bytes = ByteArray(entry.size.toInt()).also { archive.readFully(it) }
                    EncryptedHlsTarContainer.verifyEntry(entry, bytes)
                    assertArrayEquals(if (path == "index.m3u8s") manifest else segment, bytes)
                }
            }
        } finally {
            root.deleteRecursively()
            output.delete()
        }
    }

    @Serializable private data class Index(
        val version: Int,
        val containerLength: Long,
        val manifestPath: String,
        val entries: List<Entry>
    )
    @Serializable private data class Entry(
        val path: String,
        val headerOffset: Long,
        val dataOffset: Long,
        val size: Long,
        @ByteString
        val sha256: ByteArray
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `reads Qt compatible leading CBOR index`() {
        val manifest = "#EXTM3U\n".toByteArray()
        val indexBytes = Cbor.encodeToByteArray(Index(
            version = 1,
            containerLength = 3072,
            manifestPath = "index.m3u8s",
            entries = listOf(Entry("index.m3u8s", 1024, 1536, manifest.size.toLong(), TsslCrypto.sha256Hex(manifest).toByteArray()))
        ))
        val prefix = tarHeader(".vibe/index.cbor", indexBytes.size.toLong()) + indexBytes
        val parsed = EncryptedHlsTarContainer.readIndexPrefix(prefix, 3072)
        assertEquals("index.m3u8s", parsed.manifestPath)
        assertEquals(TsslCrypto.sha256Hex(indexBytes), parsed.sha256)
        EncryptedHlsTarContainer.verifyEntry(parsed.entries.getValue("index.m3u8s"), manifest)
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `rejects unsafe or out of bounds index entry`() {
        val indexBytes = Cbor.encodeToByteArray(Index(
            1, 2560, "index.m3u8s",
            listOf(Entry("../index.m3u8s", 1024, 1536, 1, "00".repeat(32).toByteArray()))
        ))
        try {
            EncryptedHlsTarContainer.readIndexPrefix(
                tarHeader(".vibe/index.cbor", indexBytes.size.toLong()) + indexBytes,
                2560
            )
            fail("Unsafe path must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun tarHeader(path: String, size: Long): ByteArray {
        val header = ByteArray(512)
        path.toByteArray().copyInto(header)
        "0000644\u0000".toByteArray().copyInto(header, 100)
        "0000000\u0000".toByteArray().copyInto(header, 108)
        "0000000\u0000".toByteArray().copyInto(header, 116)
        size.toString(8).padStart(11, '0').plus('\u0000').toByteArray().copyInto(header, 124)
        "00000000000\u0000".toByteArray().copyInto(header, 136)
        repeat(8) { header[148 + it] = ' '.code.toByte() }
        header[156] = '0'.code.toByte()
        "ustar\u0000".toByteArray().copyInto(header, 257)
        "00".toByteArray().copyInto(header, 263)
        val checksum = header.sumOf { it.toInt() and 0xff }
        val field = checksum.toString(8).padStart(6, '0') + "\u0000 "
        field.toByteArray().copyInto(header, 148)
        return header
    }
}
