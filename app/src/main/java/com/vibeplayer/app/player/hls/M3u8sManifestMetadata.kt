package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument

internal data class M3u8sManifestMetadata(
    val identifier: String,
    val encryptedSourceName: ByteArray?
)

/** Strictly extracts the Qt M3U8S root metadata needed to bind a manifest to TSSL. */
internal fun parseM3u8sManifestMetadata(manifest: ByteArray): M3u8sManifestMetadata? = runCatching {
    require(manifest.isNotEmpty() && manifest.size <= 4 * 1024 * 1024)
    val text = manifest.toString(Charsets.UTF_8)
    require(text.toByteArray(Charsets.UTF_8).contentEquals(manifest))
    val lines = text.lineSequence().map { it.trimEnd('\r').trim() }.toList()
    require(lines.firstOrNull { it.isNotEmpty() } == "#EXTM3U")
    require(lines.none { it.startsWith("#EXT-X-KEY:") || it.startsWith("#EXT-X-SESSION-KEY:") })

    val identifiers = lines.filter { it.startsWith(IDENTIFIER_PREFIX) }
    require(identifiers.size == 1)
    val identifier = identifiers.single().removePrefix(IDENTIFIER_PREFIX)
    require(identifier.length == TsslDocument.IDENTIFIER_LENGTH &&
        identifier.all { it.isLetterOrDigit() || it == '_' || it == '-' })

    val sourceLines = lines.filter { it.startsWith(SOURCE_NAME_PREFIX) }
    require(sourceLines.size <= 1)
    val encrypted = sourceLines.singleOrNull()?.removePrefix(SOURCE_NAME_PREFIX)?.let { encoded ->
        require(encoded.isNotEmpty() && '=' !in encoded && encoded.all {
            it.isLetterOrDigit() || it == '_' || it == '-'
        })
        TsslCrypto.fromBase64Url(encoded).also { block ->
            require(block.size > 32 && block.size <= 16 + 4096 + 16)
            require(TsslCrypto.toBase64Url(block) == encoded)
        }
    }
    M3u8sManifestMetadata(identifier, encrypted)
}.getOrNull()

private const val IDENTIFIER_PREFIX = "#M3U8S-IDENTIFIER:"
private const val SOURCE_NAME_PREFIX = "#M3U8S-SOURCE-NAME:"
