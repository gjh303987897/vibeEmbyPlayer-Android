package com.vibeplayer.app.player

import okhttp3.HttpUrl
import java.util.Locale

data class HlsVariant(val url: String, val width: Int = 0, val height: Int = 0, val bitrate: Long = 0)

internal fun hlsAttributes(line: String): Map<String, String> =
    Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))(?:,|$)""", RegexOption.IGNORE_CASE)
        .findAll(line.substringAfter(':')).associate {
            it.groupValues[1].uppercase(Locale.ROOT) to (it.groups[2]?.value ?: it.groupValues[3])
        }

fun parseHlsVariants(text: String, base: HttpUrl): List<HlsVariant> {
    var attributes: Map<String, String>? = null
    val variants = mutableListOf<HlsVariant>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXT-X-STREAM-INF:")) attributes = hlsAttributes(line)
        else if (line.isNotEmpty() && !line.startsWith('#')) {
            val info = attributes ?: continue
            attributes = null
            val url = base.resolve(line)?.takeIf { it.scheme in listOf("http", "https") } ?: continue
            val size = info["RESOLUTION"].orEmpty().split('x', ignoreCase = true)
            variants += HlsVariant(url.toString(), size.getOrNull(0)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                size.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                (info["AVERAGE-BANDWIDTH"] ?: info["BANDWIDTH"])?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
            if (variants.size >= 64) break
        }
    }
    return variants.distinctBy { it.url }
}

/** Keep the master and its media/key/session tags, so external audio/subtitles are not lost. */
internal fun selectHlsVariant(text: String, base: HttpUrl, selected: HlsVariant?): String {
    if (selected == null) return text
    val variants = parseHlsVariants(text, base)
    // Live services may rotate signed URLs on each master request. Retain the selected
    // resolution/bitrate instead of silently letting IJK choose a different quality.
    val choice = variants.firstOrNull { it.url == selected.url }
        ?: variants.filter {
            if (selected.width > 0 && selected.height > 0) it.width == selected.width && it.height == selected.height
            else selected.bitrate > 0 && it.bitrate == selected.bitrate
        }.minByOrNull { kotlin.math.abs(it.bitrate - selected.bitrate) }
        ?: return text
    val selectedUrl = choice.url
    val lines = text.lines()
    var keepUri = true
    var pending = false
    return buildList {
        for ((index, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) continue
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val uri = lines.asSequence().drop(index + 1).map { it.trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                keepUri = uri != null && base.resolve(uri)?.toString() == selectedUrl
                pending = true
                if (keepUri) add(raw)
            } else if (pending && line.isNotEmpty() && !line.startsWith('#')) {
                if (keepUri) add(raw)
                pending = false
            } else add(raw)
        }
    }.joinToString("\n")
}
