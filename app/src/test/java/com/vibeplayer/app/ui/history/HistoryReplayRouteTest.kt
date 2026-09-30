package com.vibeplayer.app.ui.history

import com.vibeplayer.app.model.PlaybackHistoryEntry
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryReplayRouteTest {
    @Test
    fun `each source replays through its own player`() {
        val path = "/Films/Example Movie.mkv"
        val url = "https://example.test/live/channel.m3u8?token=abc"
        val localUri = "content://media/external/video/media/42"

        assertEquals(Routes.player("server", "item"), historyReplayRoute(entry(PlaybackSource.EMBY, "item")))
        assertEquals(Routes.player("server", "item"), historyReplayRoute(entry(PlaybackSource.JELLYFIN, "item")))
        assertEquals(Routes.webdavPlayer("server", path), historyReplayRoute(entry(PlaybackSource.WEBDAV, path)))
        assertEquals(Routes.iptvPlayer("server", url, "Channel"), historyReplayRoute(entry(PlaybackSource.IPTV, url)))
        assertEquals(Routes.localPlayer(localUri), historyReplayRoute(entry(PlaybackSource.LOCAL, localUri)))
        assertEquals(Routes.linkPlayer(url), historyReplayRoute(entry(PlaybackSource.LINK, url)))
    }

    @Test
    fun `unavailable and incomplete rows have no replay route`() {
        assertNull(historyReplayRoute(entry(PlaybackSource.UNKNOWN, "item")))
        assertNull(historyReplayRoute(entry(PlaybackSource.WEBDAV, "")))
        assertNull(historyReplayRoute(entry(PlaybackSource.IPTV, "url", serviceId = "")))
        assertNull(historyReplayRoute(entry(PlaybackSource.LINK, "url", available = false)))
    }

    private fun entry(
        source: PlaybackSource,
        target: String,
        serviceId: String = "server",
        available: Boolean = true
    ) = PlaybackHistoryEntry(
        id = "history-id",
        source = source,
        serviceId = serviceId,
        serviceName = "Service",
        replayTarget = target,
        title = "Channel",
        available = available
    )
}
