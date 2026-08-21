package com.vibeplayer.app.domain.tssl

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed TSSL v2/v3 secret document.
 *
 * v3 adds encrypted source-name metadata; v2 (no `sourceName` object) remains
 * readable. Mixing v2/v3 fields unsupported by the parsed version is rejected
 * instead of silently downgrading security (see [validate]).
 */
data class TsslSegment(val path: String, val key: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is TsslSegment && other.path == path && other.key.contentEquals(key)

    override fun hashCode(): Int = path.hashCode() * 31 + key.contentHashCode()
}

data class TsslManifest(val path: String, val sha256: String)

data class TsslSourceName(
    /** `IV | ciphertext | tag` of the encrypted source basename. */
    val encrypted: ByteArray,
    /** 32-byte AES key protecting the source name. */
    val key: ByteArray
)

data class TsslDocument(
    val format: String,
    val version: Int,
    val algorithm: String,
    val identifier: String,
    val rootManifestSha256: String,
    val sourceName: TsslSourceName?,
    val manifests: List<TsslManifest>,
    val segments: List<TsslSegment>,
    /** Map of auxiliary resource path -> sha256 (may be empty). */
    val resources: Map<String, String>
) {

    companion object {

        const val FORMAT = "TSSL"
        const val ALGORITHM = "AES-256-GCM"

        /** Parses and validates a TSSL document; returns null when malformed or unsupported. */
        fun parse(bytes: ByteArray): TsslDocument? {
            return try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val format = json.optString("format")
                val version = json.optInt("version")
                val algorithm = json.optString("algorithm")
                val identifier = json.optString("identifier")
                if (format != FORMAT || (version != 2 && version != 3) ||
                    algorithm != ALGORITHM || identifier.isEmpty()
                ) {
                    return null
                }

                val sourceName = json.optJSONObject("sourceName")?.let {
                    val enc = it.optString("encrypted")
                    val key = it.optString("key")
                    if (enc.isEmpty() || key.isEmpty()) null
                    else TsslSourceName(
                        encrypted = TsslCrypto.fromBase64Url(enc),
                        key = TsslCrypto.fromBase64Url(key)
                    )
                }

                val manifests = json.optJSONArray("manifests")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.getJSONObject(i)
                            val p = o.optString("path")
                            val h = o.optString("sha256")
                            if (p.isEmpty() || h.isEmpty()) null else TsslManifest(p, h)
                        }
                    } ?: emptyList()

                val segments = json.optJSONArray("segments")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val o = arr.getJSONObject(i)
                            val p = o.optString("path")
                            val k = o.optString("key")
                            if (p.isEmpty() || k.isEmpty()) null else TsslSegment(p, TsslCrypto.fromBase64Url(k))
                        }
                    } ?: emptyList()

                val resources = mutableMapOf<String, String>()
                json.optJSONArray("resources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val p = o.optString("path")
                        val h = o.optString("sha256")
                        if (p.isNotEmpty() && h.isNotEmpty()) resources[p] = h
                    }
                }

                TsslDocument(
                    format = format,
                    version = version,
                    algorithm = algorithm,
                    identifier = identifier,
                    rootManifestSha256 = json.optString("rootManifestSha256"),
                    sourceName = sourceName,
                    manifests = manifests,
                    segments = segments,
                    resources = resources
                )
            } catch (_: Exception) {
                null
            }
        }

        /** Serialises [doc] back to the TSSL JSON byte form. */
        fun toJsonBytes(doc: TsslDocument): ByteArray {
            val json = JSONObject()
            json.put("format", doc.format)
            json.put("version", doc.version)
            json.put("algorithm", doc.algorithm)
            json.put("identifier", doc.identifier)
            json.put("rootManifestSha256", doc.rootManifestSha256)
            if (doc.sourceName != null) {
                val sn = JSONObject()
                sn.put("encrypted", TsslCrypto.toBase64Url(doc.sourceName.encrypted))
                sn.put("key", TsslCrypto.toBase64Url(doc.sourceName.key))
                json.put("sourceName", sn)
            }
            json.put("manifests", JSONArray().also { arr -> doc.manifests.forEach { m -> arr.put(JSONObject().put("path", m.path).put("sha256", m.sha256)) } })
            val segArr = JSONArray()
            doc.segments.forEach { s -> segArr.put(JSONObject().put("path", s.path).put("key", TsslCrypto.toBase64Url(s.key))) }
            json.put("segments", segArr)
            val resArr = JSONArray()
            doc.resources.forEach { (p, h) -> resArr.put(JSONObject().put("path", p).put("sha256", h)) }
            json.put("resources", resArr)
            return json.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
