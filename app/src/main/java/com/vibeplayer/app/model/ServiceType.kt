package com.vibeplayer.app.model

/** Supported media service types. Mirrors the Qt reference ServiceType. */
enum class ServiceType {
    EMBY,
    JELLYFIN,
    IPTV,
    WEBDAV,
    LINK;

    val displayName: String
        get() = when (this) {
            EMBY -> "Emby"
            JELLYFIN -> "Jellyfin"
            IPTV -> "IPTV"
            WEBDAV -> "WebDAV"
            LINK -> "Link"
        }

    companion object {
        fun fromDisplayName(value: String): ServiceType = when (value) {
            "Jellyfin" -> JELLYFIN
            "IPTV" -> IPTV
            "WebDAV" -> WEBDAV
            "Link" -> LINK
            else -> EMBY
        }
    }
}
