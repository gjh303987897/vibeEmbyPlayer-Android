package com.vibeplayer.app.domain.tssl

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Parsed and validated TSSL v2/v3/v4 secret document. */
data class TsslSegment(val path: String, val key: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TsslSegment && other.path == path && other.key.contentEquals(key)
    override fun hashCode(): Int = path.hashCode() * 31 + key.contentHashCode()
}

data class TsslManifest(val path: String, val sha256: String)

data class TsslSourceName(val encrypted: ByteArray, val key: ByteArray)

data class TsslDocument(
    val format: String,
    val version: Int,
    val algorithm: String,
    val identifier: String,
    val rootManifestSha256: String,
    val sourceName: TsslSourceName?,
    val manifests: List<TsslManifest>,
    val segments: List<TsslSegment>,
    val resources: Map<String, String>,
    /** TSSL v4 single-file container metadata; absent for v2/v3 directory packages. */
    val containerFormat: String? = null,
    val containerIndexSha256: String? = null,
    val containerLength: Long? = null
) {
    companion object {
        const val FORMAT = "TSSL"
        const val ALGORITHM = "AES-256-GCM"
        const val V4_CONTAINER_FORMAT = "m3u8sp-tar-index-v1"
        const val IDENTIFIER_LENGTH = 4096
        private const val MAX_PATH_CHARS = 4096
        private const val SOURCE_NAME_AAD = "vibeEmbyPlayerQT/M3U8S/source-name/v1\n"
        private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{4096}$")
        private val SHA256 = Regex("^[0-9a-fA-F]{64}$")

        /** Parses the complete v2/v3/v4 contract; malformed or mixed-version fields are rejected. */
        fun parse(bytes: ByteArray): TsslDocument? = runCatching {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val format = json.optString("format")
            val version = json.optInt("version", -1)
            val algorithm = json.optString("algorithm")
            val identifier = json.optString("identifier")
            val rootDigest = json.optString("rootManifestSha256")
            require(format == FORMAT && version in 2..4 && algorithm == ALGORITHM)
            require(IDENTIFIER.matches(identifier) && SHA256.matches(rootDigest))

            val sourceName = if (version >= 3) {
                val source = json.getJSONObject("sourceName")
                val encrypted = decodeCanonicalBase64(source.getString("encrypted"))
                val key = decodeCanonicalBase64(source.getString("key"))
                require(encrypted.size > 32 && key.size == TsslCrypto.KEY_BYTES)
                TsslSourceName(encrypted, key)
            } else {
                require(!json.has("sourceName"))
                null
            }

            val containerFormat: String?
            val containerIndexSha256: String?
            val containerLength: Long?
            if (version == 4) {
                containerFormat = json.getString("containerFormat")
                containerIndexSha256 = json.getString("containerIndexSha256")
                containerLength = json.getLong("containerLength")
                require(containerFormat == V4_CONTAINER_FORMAT)
                require(SHA256.matches(containerIndexSha256) && containerLength > 0)
            } else {
                require(!json.has("containerFormat") && !json.has("containerIndexSha256") && !json.has("containerLength"))
                containerFormat = null
                containerIndexSha256 = null
                containerLength = null
            }

            val seen = mutableSetOf<String>()
            val manifests = parseDigestEntries(json.getJSONArray("manifests"), seen)
                .map { TsslManifest(it.first, it.second) }
            val segments = parseKeyEntries(json.getJSONArray("segments"), seen)
            require(segments.isNotEmpty())
            require(segments.all { it.path.endsWith(".ts", ignoreCase = true) })
            val resources = if (json.has("resources")) {
                parseDigestEntries(json.getJSONArray("resources"), seen).toMap()
            } else emptyMap()
            require(manifests.all {
                it.path.endsWith(".m3u8", ignoreCase = true) ||
                    it.path.endsWith(".m3u8s", ignoreCase = true)
            })

            sourceName?.let { source ->
                val qtAad = (SOURCE_NAME_AAD + identifier).toByteArray(Charsets.UTF_8)
                val legacyAad = identifier.toByteArray(Charsets.UTF_8)
                val plain = if (version == 3) {
                    // Android v3 packages created before Qt AAD alignment used
                    // identifier-only AAD. Keep that legacy read path for v3 only;
                    // v4 must always satisfy the exact Qt contract.
                    runCatching { TsslCrypto.decrypt(source.key, source.encrypted, qtAad) }
                        .recoverCatching { TsslCrypto.decrypt(source.key, source.encrypted, legacyAad) }
                        .getOrThrow()
                } else {
                    TsslCrypto.decrypt(source.key, source.encrypted, qtAad)
                }
                val name = plain.toString(Charsets.UTF_8)
                require(name.isNotBlank() && name != "." && name != ".." &&
                    name.toByteArray(Charsets.UTF_8).contentEquals(plain) &&
                    !name.contains('/') && !name.contains('\\') &&
                    name.none { it.code < 0x20 || it.code == 0x7f })
            }

            TsslDocument(
                format, version, algorithm, identifier, rootDigest, sourceName,
                manifests, segments, resources, containerFormat,
                containerIndexSha256?.lowercase(), containerLength
            )
        }.getOrNull()

        fun toJsonBytes(doc: TsslDocument): ByteArray {
            val json = JSONObject()
                .put("format", doc.format)
                .put("version", doc.version)
                .put("algorithm", doc.algorithm)
                .put("identifier", doc.identifier)
                .put("rootManifestSha256", doc.rootManifestSha256)
            doc.sourceName?.let { source ->
                json.put("sourceName", JSONObject()
                    .put("encrypted", Base64.getEncoder().encodeToString(source.encrypted))
                    .put("key", Base64.getEncoder().encodeToString(source.key)))
            }
            if (doc.version == 4) {
                json.put("containerFormat", doc.containerFormat)
                    .put("containerIndexSha256", doc.containerIndexSha256)
                    .put("containerLength", doc.containerLength)
            }
            json.put("manifests", JSONArray().also { array ->
                doc.manifests.sortedBy { it.path }.forEach { item ->
                    array.put(JSONObject().put("path", item.path).put("sha256", item.sha256))
                }
            })
            json.put("segments", JSONArray().also { array ->
                doc.segments.sortedBy { it.path }.forEach { item ->
                    array.put(JSONObject().put("path", item.path)
                        .put("key", Base64.getEncoder().encodeToString(item.key)))
                }
            })
            json.put("resources", JSONArray().also { array ->
                doc.resources.toSortedMap().forEach { (path, digest) ->
                    array.put(JSONObject().put("path", path).put("sha256", digest))
                }
            })
            return json.toString().toByteArray(Charsets.UTF_8)
        }

        private fun parseDigestEntries(array: JSONArray, seen: MutableSet<String>): List<Pair<String, String>> =
            (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val path = checkedPath(value.getString("path"), seen)
                val digest = value.getString("sha256")
                require(SHA256.matches(digest))
                path to digest.lowercase()
            }

        private fun parseKeyEntries(array: JSONArray, seen: MutableSet<String>): List<TsslSegment> =
            (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val path = checkedPath(value.getString("path"), seen)
                val key = decodeCanonicalBase64(value.getString("key"))
                require(key.size == TsslCrypto.KEY_BYTES)
                TsslSegment(path, key)
            }

        private fun checkedPath(path: String, seen: MutableSet<String>): String {
            require(path.isNotEmpty() && path.length <= MAX_PATH_CHARS)
            require(!path.startsWith('/') && !path.contains('\\') && !path.contains('?') && !path.contains('#'))
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." })
            require(seen.add(path))
            return path
        }

        private fun decodeCanonicalBase64(value: String): ByteArray {
            val standard = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
            if (standard != null && Base64.getEncoder().encodeToString(standard) == value) return standard
            // Read Android's earlier unpadded Base64URL output for v2/v3 compatibility.
            return Base64.getUrlDecoder().decode(value).also {
                require(Base64.getUrlEncoder().withoutPadding().encodeToString(it) == value)
            }
        }
    }
}
