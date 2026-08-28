package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3u8sManifestMetadataTest {
    private val identifier = "A".repeat(TsslDocument.IDENTIFIER_LENGTH)
    private val encrypted = ByteArray(33) { it.toByte() }

    @Test
    fun `extracts exact identifier and canonical source ciphertext`() {
        val metadata = parseM3u8sManifestMetadata(manifest())!!
        assertEquals(identifier, metadata.identifier)
        assertArrayEquals(encrypted, metadata.encryptedSourceName)
    }

    @Test
    fun `rejects duplicate missing or malformed metadata`() {
        val sourceLine = "#M3U8S-SOURCE-NAME:${TsslCrypto.toBase64Url(encrypted)}"
        val idLine = "#M3U8S-IDENTIFIER:$identifier"
        listOf(
            "#EXTM3U\n$sourceLine\n",
            "#EXTM3U\n$idLine\n$idLine\n$sourceLine\n",
            "#EXTM3U\n$idLine\n$sourceLine\n$sourceLine\n",
            "#EXTM3U\n$idLine\n#M3U8S-SOURCE-NAME:bad=\n",
            "#EXTM3U\n$idLine\n$sourceLine\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n"
        ).forEach { assertNull(parseM3u8sManifestMetadata(it.toByteArray())) }
    }

    private fun manifest(): ByteArray = buildString {
        appendLine("#EXTM3U")
        appendLine("#M3U8S-IDENTIFIER:$identifier")
        appendLine("#M3U8S-SOURCE-NAME:${TsslCrypto.toBase64Url(encrypted)}")
        appendLine("#EXTINF:1.0,")
        appendLine("segment_000001.ts")
    }.toByteArray()
}
