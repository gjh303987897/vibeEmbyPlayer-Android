package com.vibeplayer.app.domain.tssl

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TsslDocumentV4Test {
    private val identifier = "A".repeat(TsslDocument.IDENTIFIER_LENGTH)
    private val digest = "11".repeat(32)
    private val key = ByteArray(32) { it.toByte() }
    private val encrypted: ByteArray
        get() = TsslCrypto.encrypt(
            key,
            ByteArray(TsslCrypto.IV_BYTES) { (it + 1).toByte() },
            "movie.mkv".toByteArray(),
            ("vibeEmbyPlayerQT/M3U8S/source-name/v1\n" + identifier).toByteArray()
        )

    @Test
    fun `v4 round trips container binding with Qt canonical base64`() {
        val document = TsslDocument(
            format = TsslDocument.FORMAT,
            version = 4,
            algorithm = TsslDocument.ALGORITHM,
            identifier = identifier,
            rootManifestSha256 = digest,
            sourceName = TsslSourceName(encrypted, key),
            manifests = emptyList(),
            segments = listOf(TsslSegment("segment_000001.ts", key)),
            resources = emptyMap(),
            containerFormat = TsslDocument.V4_CONTAINER_FORMAT,
            containerIndexSha256 = "22".repeat(32),
            containerLength = 4096
        )
        val encoded = TsslDocument.toJsonBytes(document)
        val parsed = TsslDocument.parse(encoded)
        assertNotNull(parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(4096L, parsed.containerLength)
        val json = JSONObject(String(encoded))
        assertEquals(Base64.getEncoder().encodeToString(key), json.getJSONArray("segments").getJSONObject(0).getString("key"))
    }

    @Test
    fun `v4 rejects missing or invalid container binding`() {
        val base = validV4Json()
        listOf("containerFormat", "containerIndexSha256", "containerLength").forEach { field ->
            val json = JSONObject(base.toString()).apply { remove(field) }
            assertNull(field, TsslDocument.parse(json.toString().toByteArray()))
        }
        assertNull(TsslDocument.parse(JSONObject(base.toString()).put("containerLength", 0).toString().toByteArray()))
    }

    @Test
    fun `v4 rejects Android legacy identifier-only source-name AAD`() {
        val legacyEncrypted = TsslCrypto.encrypt(
            key,
            ByteArray(TsslCrypto.IV_BYTES) { (it + 1).toByte() },
            "movie.mkv".toByteArray(),
            identifier.toByteArray()
        )
        val json = validV4Json()
        json.getJSONObject("sourceName")
            .put("encrypted", Base64.getEncoder().encodeToString(legacyEncrypted))
        assertNull(TsslDocument.parse(json.toString().toByteArray()))
    }

    @Test
    fun `v3 remains readable and rejects v4 fields`() {
        val json = validV4Json().put("version", 3)
        json.remove("containerFormat")
        json.remove("containerIndexSha256")
        json.remove("containerLength")
        assertNotNull(TsslDocument.parse(json.toString().toByteArray()))
        json.put("containerLength", 12)
        assertNull(TsslDocument.parse(json.toString().toByteArray()))
    }

    private fun validV4Json(): JSONObject = JSONObject()
        .put("format", TsslDocument.FORMAT)
        .put("version", 4)
        .put("algorithm", TsslDocument.ALGORITHM)
        .put("identifier", identifier)
        .put("rootManifestSha256", digest)
        .put("sourceName", JSONObject()
            .put("encrypted", Base64.getEncoder().encodeToString(encrypted))
            .put("key", Base64.getEncoder().encodeToString(key)))
        .put("containerFormat", TsslDocument.V4_CONTAINER_FORMAT)
        .put("containerIndexSha256", "22".repeat(32))
        .put("containerLength", 4096)
        .put("manifests", org.json.JSONArray())
        .put("segments", org.json.JSONArray().put(JSONObject()
            .put("path", "segment_000001.ts")
            .put("key", Base64.getEncoder().encodeToString(key))))
        .put("resources", org.json.JSONArray())
}
