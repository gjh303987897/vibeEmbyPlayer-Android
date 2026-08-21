package com.vibeplayer.app.data.remote.iptv

import java.nio.charset.Charset
import java.security.MessageDigest

/**
 * M3U / M3U8 playlist parser ported from the Qt IptvParser.
 *
 * Reads channel metadata from #EXTINF lines (tvg-name, group-title, tvg-logo
 * and the title after the comma), uses the next non-comment line as the stream
 * URL, defaults the group to "Default", and derives a stable SHA-256 channel id
 * from name|group|streamUrl. Character encoding falls back UTF-8 -> GB18030 ->
 * GBK (Qt also tries the system encoding; Android's default is UTF-8).
 */
object IptvParser {

    const val DEFAULT_GROUP = "Default"

    fun decodeText(bytes: ByteArray): String {
        val utf8 = tryDecode(bytes, Charset.forName("UTF-8"))
        if (!utf8.contains('\uFFFD')) return utf8

        val gb18030 = tryDecode(bytes, Charset.forName("GB18030"))
        if (!gb18030.contains('\uFFFD')) return gb18030

        val gbk = tryDecode(bytes, Charset.forName("GBK"))
        if (!gbk.contains('\uFFFD')) return gbk

        return utf8
    }

    fun looksLikeHlsManifest(text: String): Boolean {
        val upper = text.uppercase()
        return upper.contains("#EXT-X-STREAM-INF")
            || upper.contains("#EXT-X-TARGETDURATION")
            || upper.contains("#EXT-X-MEDIA-SEQUENCE")
    }

    /**
     * Parses a normal (non-HLS) M3U playlist into channels. Returns an empty
     * list when no channels were found.
     */
    fun parseChannels(text: String, fallbackName: String): List<ParsedChannel> {
        val channels = mutableListOf<ParsedChannel>()
        var pendingName: String? = null
        var pendingGroup = DEFAULT_GROUP
        var pendingLogo = ""
        var hasPending = false
        var sortOrder = 0

        val lines = text.split(Regex("\\r\\n|\\n|\\r")).map { it.trim() }
        for (line in lines) {
            if (line.isEmpty()) continue

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attributes = parseAttributes(line)
                var name = attributes["tvg-name"].orEmpty()
                if (name.isEmpty()) name = extinfTitle(line)
                if (name.isEmpty()) name = fallbackName
                pendingName = name
                pendingGroup = attributes["group-title"]?.takeIf { it.isNotEmpty() } ?: DEFAULT_GROUP
                pendingLogo = attributes["tvg-logo"].orEmpty()
                hasPending = true
                continue
            }

            if (!isUrlLine(line)) continue

            val name = pendingName
                ?: lineBaseName(line).takeIf { it.isNotEmpty() }
                    ?: if (fallbackName.isEmpty()) "IPTV Channel" else fallbackName
            val group = if (hasPending) pendingGroup else DEFAULT_GROUP
            channels += ParsedChannel(
                id = stableId("$name|$group|$line"),
                name = name,
                groupName = group,
                logoUrl = if (hasPending) pendingLogo else "",
                streamUrl = line,
                sortOrder = sortOrder++
            )
            hasPending = false
        }
        return channels
    }

    fun stableId(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class ParsedChannel(
        val id: String,
        val name: String,
        val groupName: String,
        val logoUrl: String,
        val streamUrl: String,
        val sortOrder: Int
    )

    private fun tryDecode(bytes: ByteArray, charset: Charset): String = runCatching {
        String(bytes, charset)
    }.getOrDefault("")

    private fun parseAttributes(value: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
        pattern.findAll(value).forEach { match ->
            result[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        return result
    }

    private fun extinfTitle(line: String): String {
        val commaIndex = line.indexOf(',')
        if (commaIndex < 0 || commaIndex + 1 >= line.length) return ""
        return cleanName(line.substring(commaIndex + 1))
    }

    private fun cleanName(value: String): String {
        var v = value.trim()
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length - 1).trim()
        }
        return v
    }

    private fun isUrlLine(line: String): Boolean {
        if (line.isEmpty() || line.startsWith("#")) return false
        return runCatching { java.net.URI(line).isAbsolute }.getOrDefault(false) ||
            line.endsWith(".m3u8", ignoreCase = true) ||
            line.endsWith(".m3u", ignoreCase = true)
    }

    private fun lineBaseName(url: String): String = runCatching {
        val path = java.net.URI(url).path.orEmpty()
        val name = path.substringAfterLast('/')
        name.substringBeforeLast('.').trim().ifEmpty { name }
    }.getOrDefault("")
}
