package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslDocument
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Validates every HLS URI before a verified playlist is exposed to Media3. */
internal fun validateHlsPlaylist(
    bytes: ByteArray,
    manifestPath: String,
    document: TsslDocument,
    root: Boolean
): Boolean = runCatching {
    require(bytes.isNotEmpty() && bytes.size <= 4 * 1024 * 1024)
    val text = bytes.toString(Charsets.UTF_8)
    require(text.toByteArray(Charsets.UTF_8).contentEquals(bytes) && !text.startsWith('\uFEFF'))
    val lines = text.lineSequence().map { it.trimEnd('\r').trim() }.toList()
    require(lines.firstOrNull { it.isNotEmpty() } == "#EXTM3U")
    val registered = buildSet {
        addAll(document.manifests.map { it.path })
        addAll(document.segments.map { it.path })
        addAll(document.resources.keys)
    }
    val uriAttribute = Regex("(?:^|[:,])URI=\"([^\"]*)\"")
    lines.dropWhile { it.isEmpty() }.drop(1).forEach { line ->
        if (line.isEmpty()) return@forEach
        require(!line.startsWith("#EXT-X-KEY:") && !line.startsWith("#EXT-X-SESSION-KEY:"))
        if (!line.startsWith('#')) {
            require(resolvePlaylistUri(line, manifestPath) in registered)
        } else {
            val matches = uriAttribute.findAll(line).toList()
            matches.forEach { require(resolvePlaylistUri(it.groupValues[1], manifestPath) in registered) }
            require("URI=" !in line || matches.isNotEmpty())
        }
    }
    if (root) require(parseM3u8sManifestMetadata(bytes) != null)
    true
}.getOrDefault(false)

internal fun resolvePlaylistUri(uriText: String, manifestPath: String): String? = runCatching {
    require(uriText.isNotEmpty() && !uriText.contains('\\'))
    val uri = URI(uriText)
    require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawFragment == null)
    val decoded = URLDecoder.decode(uri.rawPath, StandardCharsets.UTF_8.toString())
    require(decoded.isNotEmpty() && !decoded.startsWith('/') && !decoded.contains('\\'))
    val directory = manifestPath.substringBeforeLast('/', "")
    val components = mutableListOf<String>()
    (if (directory.isEmpty()) decoded else "$directory/$decoded").split('/').forEach { component ->
        when (component) {
            "", "." -> Unit
            ".." -> require(components.isNotEmpty()).also { components.removeAt(components.lastIndex) }
            else -> components += component
        }
    }
    require(components.isNotEmpty())
    components.joinToString("/")
}.getOrNull()
