package com.vibeplayer.app.model

/**
 * Playback history source. Mirrors the Qt reference PlaybackHistorySource.
 */
enum class PlaybackSource(val label: String) {
    EMBY("Emby"),
    JELLYFIN("Jellyfin"),
    IPTV("IPTV"),
    WEBDAV("WebDAV"),
    LOCAL("Local"),
    LINK("Link"),
    UNKNOWN("Unknown");

    companion object {
        fun fromLabel(value: String): PlaybackSource = when (value) {
            "Emby" -> EMBY
            "Jellyfin" -> JELLYFIN
            "IPTV" -> IPTV
            "WebDAV" -> WEBDAV
            "Local" -> LOCAL
            "Link" -> LINK
            else -> UNKNOWN
        }

        fun ofServiceType(type: ServiceType): PlaybackSource = when (type) {
            ServiceType.EMBY -> EMBY
            ServiceType.JELLYFIN -> JELLYFIN
            ServiceType.IPTV -> IPTV
            ServiceType.WEBDAV -> WEBDAV
            ServiceType.LINK -> LINK
        }
    }
}
