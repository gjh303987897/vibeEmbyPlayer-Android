package com.vibeplayer.app.domain.local

/**
 * Local media file support helpers ported from the Qt LocalMediaService.
 * IJKPlayer exposes the decoder path through its media metadata and callbacks.
 */
object LocalPlaybackService {

    private val VIDEO_EXTENSIONS = setOf(
        "3g2", "3gp", "asf", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4",
        "mpeg", "mpg", "mts", "ogm", "ogv", "rm", "rmvb", "ts", "vob", "webm", "wmv"
    )

    /** Encrypted-HLS package manifests are browsable and playable through the proxy. */
    private val PLAYLIST_EXTENSIONS = setOf("m3u8s", "m3u8sp")

    private val GENERATED_SEGMENT = Regex("^segment_\\d{6}\\.ts$", RegexOption.IGNORE_CASE)

    fun isSupportedVideoFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS

    /** True for any centrally browsable media file (video or encrypted-HLS manifest). */
    fun isBrowsableMediaFile(name: String): Boolean =
        isSupportedVideoFile(name) || name.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS

    fun isEncryptedHlsManifest(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS

    fun isGeneratedSegment(name: String): Boolean = GENERATED_SEGMENT.matches(name)
}
