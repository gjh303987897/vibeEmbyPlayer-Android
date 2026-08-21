package com.vibeplayer.app.ui.navigation

import java.util.Base64

/** Route specifications for the media browsing flow. */
object Routes {

    const val HOME = "home/{serverId}"
    const val LIBRARY = "library/{serverId}/{libraryId}"
    const val DETAILS = "details/{serverId}/{itemId}"
    const val SEARCH = "search/{serverId}"
    const val PLAYER = "player/{serverId}/{itemId}"
    const val WEBDAV_BROWSE = "webdav/{serverId}"
    const val WEBDAV_PLAYER = "webdavPlayer/{serverId}/{path}"
    const val IPTV_HOME = "iptv/{serverId}"
    const val IPTV_PLAYER = "iptvPlayer/{serverId}/{url}/{name}"
    const val LINK_HOME = "link/{serverId}"
    const val LINK_PLAYER = "linkPlayer/{url}"
    const val LOCAL_HOME = "localHome"
    const val LOCAL_PLAYER = "localPlayer/{url}"
    const val TSSL_HOME = "tsslHome"

    fun home(serverId: String) = "home/$serverId"
    fun library(serverId: String, libraryId: String) = "library/$serverId/$libraryId"
    fun details(serverId: String, itemId: String) = "details/$serverId/$itemId"
    fun search(serverId: String) = "search/$serverId"
    fun player(serverId: String, itemId: String) = "player/$serverId/$itemId"

    fun webdavBrowse(serverId: String) = "webdav/$serverId"

    /** Encodes a WebDAV path (may contain slashes) into a single URL-safe segment. */
    fun webdavPlayer(serverId: String, path: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.toByteArray(Charsets.UTF_8))
        return "webdavPlayer/$serverId/$encoded"
    }

    fun decodeWebDavPath(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun iptvHome(serverId: String) = "iptv/$serverId"

    /** Encodes an IPTV stream URL and channel name into URL-safe segments. */
    fun iptvPlayer(serverId: String, streamUrl: String, name: String): String {
        val u = Base64.getUrlEncoder().withoutPadding().encodeToString(streamUrl.toByteArray(Charsets.UTF_8))
        val n = Base64.getUrlEncoder().withoutPadding().encodeToString(name.toByteArray(Charsets.UTF_8))
        return "iptvPlayer/$serverId/$u/$n"
    }

    fun decodeIptvUrl(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun decodeIptvName(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun linkHome(serverId: String) = "link/$serverId"

    /** Encodes a link URL (may contain slashes/queries) into a URL-safe segment. */
    fun linkPlayer(url: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(url.toByteArray(Charsets.UTF_8))
        return "linkPlayer/$encoded"
    }

    fun decodeLinkUrl(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun localHome() = "localHome"

    /** Encodes a local media document/content URI into a URL-safe segment. */
    fun localPlayer(uri: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(uri.toByteArray(Charsets.UTF_8))
        return "localPlayer/$encoded"
    }

    fun decodeLocalUrl(encoded: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun tsslHome() = TSSL_HOME
}
