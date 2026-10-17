package com.vibeplayer.app.model

import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.remote.iptv.IptvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IptvChannelTest {
    private fun row(id: String, name: String = "CCTV 1", group: String = "News", tvg: String = "", playlist: String = "p") =
        IptvChannelEntity(id, playlist, name, group, "", "https://example.com/$id", 0, tvg)

    @Test fun `normalised name and group merge legacy rows without discarding favourites`() {
        val routes = listOf(row("old-a"), row("old-b", " cctv   1 ", " news "))
        val channel = mergeIptvChannels(routes).single()
        assertEquals("old-a", channel.id)
        assertEquals(listOf("https://example.com/old-a", "https://example.com/old-b"), channel.streamUrls)
        assertTrue(channel.isFavourite(setOf("old-b")))
        assertFalse(channel.isFavourite(setOf("other")))
        assertEquals(setOf("old-a", "old-b"), channel.sourceIds)
    }

    @Test fun `tvg id joins differently named routes but distinct identities stay separate`() {
        val channels = mergeIptvChannels(listOf(
            row("a", tvg = "cctv1"), row("b", "CCTV1 HD", "Other", tvg = "cctv1"),
            row("c", tvg = "regional"), row("d"), row("e", group = "Regional"),
            row("f", tvg = "cctv1", playlist = "another"), row("g", name = "CCTV 1 4K")
        ))
        assertEquals(6, channels.size)
        assertEquals(listOf("a", "b"), channels.first().sources.map { it.id })
    }

    @Test fun `duplicate urls are offered once and source order stays stable`() {
        val a = row("a").copy(sortOrder = 2)
        val b = row("b").copy(sortOrder = 1, streamUrl = a.streamUrl, logoUrl = "logo")
        val c = mergeIptvChannels(listOf(a, b)).single()
        assertEquals("b", c.id)
        assertEquals(1, c.streamUrls.size)
        assertEquals("logo", c.logoUrl)
        assertEquals(2, c.sourceIds.size)
    }

    @Test fun `parser retains tvg identity and does not copy metadata to an unlabelled url`() {
        val rows = IptvParser.parseChannels("""
            #EXTM3U
            #EXTINF:-1 tvg-id="cctv1" group-title="News",CCTV 1
            https://example.com/a.ts
            https://example.com/other.ts
        """.trimIndent(), "fallback")
        assertEquals("cctv1", rows[0].tvgId)
        assertEquals("CCTV 1", rows[0].name)
        assertEquals("", rows[1].tvgId)
        assertEquals("other", rows[1].name)
        assertEquals(IptvParser.DEFAULT_GROUP, rows[1].groupName)
        assertEquals(IptvParser.stableId("CCTV 1|News|https://example.com/a.ts"), rows[0].id)
    }
}
