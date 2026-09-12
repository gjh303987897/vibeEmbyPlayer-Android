package com.vibeplayer.app.model

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * A single WebDAV directory entry, mirrors the Qt WebDavItem.
 */
data class WebDavItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val contentType: String = "",
    val modifiedAt: String = "",
    /** Short preview of the authenticated M3U8S/TSSL identifier. */
    val identifierPreview: String? = null,
    /** Original source basename, only populated after TSSL authentication. */
    val sourceFileName: String? = null,
    /** True while encrypted-HLS metadata is being read from the remote item. */
    val metadataLoading: Boolean = false,
    /**
     * True once a metadata read finished without a usable identifier (unreadable
     * or unregistered package). The row shows this explicitly instead of going
     * blank, so a failed lookup is never mistaken for a missing field.
     */
    val metadataUnavailable: Boolean = false
) {
    val isEncryptedHls: Boolean
        get() = !isDirectory &&
            (name.endsWith(".m3u8s", ignoreCase = true) ||
                name.endsWith(".m3u8sp", ignoreCase = true))

    val isVideo: Boolean
        get() = contentType.startsWith("video/", ignoreCase = true) ||
            VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val isAudio: Boolean
        get() = contentType.startsWith("audio/", ignoreCase = true) ||
            AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".webm", ".m4v", ".ts", ".m2ts", ".mts",
            ".mpg", ".mpeg", ".wmv", ".flv", ".ogv", ".3gp", ".3g2", ".asf", ".vob",
            ".rm", ".rmvb", ".ogm", ".m3u8s", ".m3u8sp"
        )
        val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".wma")

        fun decodePath(path: String): String =
            runCatching { URLDecoder.decode(path, StandardCharsets.UTF_8.toString()) }.getOrDefault(path)
    }
}
