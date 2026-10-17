package com.vibeplayer.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvRouteTest {
    private fun route(id: String, height: Int = 0, bitrate: Long = 0,
        availability: IptvRouteAvailability = IptvRouteAvailability.AVAILABLE) =
        IptvRoute(id, 1, width = height * 16 / 9, height = height, bitrate = bitrate, availability = availability)

    @Test fun `highest and lowest prefer resolution over bitrate and never choose unknown as lowest`() {
        val routes = listOf(route("unknown"), route("sd", 360, 2_000_000), route("hd", 1080, 1_000_000),
            route("offline", 2160, availability = IptvRouteAvailability.UNAVAILABLE))
        assertEquals(listOf("hd", "sd", "unknown", "offline"), rankIptvRoutes(routes, IptvQualityPreference.HIGHEST).map { it.sourceUrl })
        assertEquals(listOf("sd", "hd", "unknown", "offline"), rankIptvRoutes(routes, IptvQualityPreference.LOWEST).map { it.sourceUrl })
    }

    @Test fun `equal resolution compares bitrate with stable ordering for unknown quality`() {
        val routes = listOf(route("a", 720, 1_000_000), route("b", 720, 3_000_000), route("c", 720),
            route("unknown-a"), route("unknown-b"))
        assertEquals(listOf("b", "a", "c", "unknown-a", "unknown-b"), rankIptvRoutes(routes, IptvQualityPreference.HIGHEST).map { it.sourceUrl })
        assertEquals(listOf("a", "b", "c", "unknown-a", "unknown-b"), rankIptvRoutes(routes, IptvQualityPreference.LOWEST).map { it.sourceUrl })
    }

    @Test fun `unknown reachability precedes confirmed failures but follows responding sources`() {
        val routes = listOf(route("failed", 1080, availability = IptvRouteAvailability.UNAVAILABLE),
            route("not-probed", 720, availability = IptvRouteAvailability.UNKNOWN), route("responding"))
        assertEquals(listOf("responding", "not-probed", "failed"), rankIptvRoutes(routes, IptvQualityPreference.HIGHEST).map { it.sourceUrl })
    }
}
