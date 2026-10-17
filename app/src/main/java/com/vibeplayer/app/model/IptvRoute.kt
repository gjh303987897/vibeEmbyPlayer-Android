package com.vibeplayer.app.model

import com.vibeplayer.app.data.remote.iptv.IptvParser

enum class IptvQualityPreference { HIGHEST, LOWEST }
enum class IptvRouteAvailability { AVAILABLE, UNKNOWN, UNAVAILABLE }

data class IptvRoute(
    val sourceUrl: String,
    val sourceNumber: Int,
    val variantUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Long = 0,
    val availability: IptvRouteAvailability = IptvRouteAvailability.UNKNOWN
) {
    val key: String get() = IptvParser.stableId("$sourceUrl\u0000${variantUrl.orEmpty()}")
    val pixels: Long get() = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)
}

/** Unknown quality is never treated as zero quality, even for LOWEST. Ties retain input order. */
fun rankIptvRoutes(routes: List<IptvRoute>, preference: IptvQualityPreference): List<IptvRoute> {
    val direction = if (preference == IptvQualityPreference.HIGHEST) -1 else 1
    fun availability(value: IptvRouteAvailability) = when (value) {
        IptvRouteAvailability.AVAILABLE -> 2
        IptvRouteAvailability.UNKNOWN -> 1
        IptvRouteAvailability.UNAVAILABLE -> 0
    }
    return routes.sortedWith { a, b ->
        compareValues(availability(b.availability), availability(a.availability)).takeIf { it != 0 }
            ?: compareValues(b.pixels > 0, a.pixels > 0).takeIf { it != 0 }
            ?: (direction * a.pixels.compareTo(b.pixels)).takeIf { it != 0 }
            ?: compareValues(b.bitrate > 0, a.bitrate > 0).takeIf { it != 0 }
            ?: direction * a.bitrate.compareTo(b.bitrate)
    }
}
