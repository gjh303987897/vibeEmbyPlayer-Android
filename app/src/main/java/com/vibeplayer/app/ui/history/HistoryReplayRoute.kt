package com.vibeplayer.app.ui.history

import com.vibeplayer.app.model.PlaybackHistoryEntry
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.ui.navigation.Routes

/** Route each persisted target through the player that understands its source. */
internal fun historyReplayRoute(entry: PlaybackHistoryEntry): String? {
    if (!entry.available || entry.replayTarget.isBlank()) return null
    return when (entry.source) {
        PlaybackSource.EMBY, PlaybackSource.JELLYFIN ->
            entry.serviceId.takeIf(String::isNotBlank)?.let { Routes.player(it, entry.replayTarget) }
        PlaybackSource.WEBDAV ->
            entry.serviceId.takeIf(String::isNotBlank)?.let { Routes.webdavPlayer(it, entry.replayTarget) }
        PlaybackSource.IPTV ->
            entry.serviceId.takeIf(String::isNotBlank)?.let {
                Routes.iptvPlayer(it, entry.replayTarget, entry.title)
            }
        PlaybackSource.LOCAL -> Routes.localPlayer(entry.replayTarget)
        PlaybackSource.LINK -> Routes.linkPlayer(entry.replayTarget)
        PlaybackSource.UNKNOWN -> null
    }
}
