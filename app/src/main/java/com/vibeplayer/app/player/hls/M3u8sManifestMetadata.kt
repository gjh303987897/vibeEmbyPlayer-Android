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

/**
 * Extracts only the identifier from a possibly truncated manifest prefix.
 *
 * A list row shows the identifier preview without pulling a manifest that can be
 * several megabytes over the wire, and the row must still render when the
 * package is only partially readable. The identifier is fixed-length Base64URL,
 * so a partial tail cannot smuggle arbitrary text into the UI. Nothing else is
 * trusted from a prefix: the filename stays hidden until a complete manifest
 * matches a local TSSL digest.
 */
internal fun parseM3u8sIdentifierPrefix(manifest: ByteArray): String? = runCatching {
    require(manifest.isNotEmpty() && manifest.size <= 4 * 1024 * 1024)
    // Latin-1 never fails on a truncated multi-byte character; the identifier
    // itself is restricted to ASCII below.
    val text = String(manifest, Charsets.ISO_8859_1)
    require(text.lineSequence().first { it.isNotBlank() }.trimEnd('\r').trim() == "#EXTM3U")
    val line = text.lineSequence()
        .map { it.trimEnd('\r').trim() }
        .filter { it.startsWith(IDENTIFIER_PREFIX) }
        .toList()
    require(line.size == 1)
    val identifier = line.single().removePrefix(IDENTIFIER_PREFIX)
    require(identifier.length == TsslDocument.IDENTIFIER_LENGTH &&
        identifier.all { it.isLetterOrDigit() || it == '_' || it == '-' })
    identifier
}.getOrNull()

private const val IDENTIFIER_PREFIX = "#M3U8S-IDENTIFIER:"
private const val SOURCE_NAME_PREFIX = "#M3U8S-SOURCE-NAME:"
