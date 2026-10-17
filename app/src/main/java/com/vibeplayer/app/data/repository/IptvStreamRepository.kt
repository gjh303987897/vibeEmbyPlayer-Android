package com.vibeplayer.app.data.repository

import com.vibeplayer.app.data.remote.iptv.IptvProbeResult
import com.vibeplayer.app.data.remote.iptv.IptvQualityProbe
import com.vibeplayer.app.model.IptvChannel
import com.vibeplayer.app.model.IptvRoute
import com.vibeplayer.app.model.IptvRouteAvailability
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class IptvStreamRepository @Inject constructor(private val probe: IptvQualityProbe) {
    private data class Key(val url: String, val trust: Boolean)
    private data class Cached(val result: IptvProbeResult, val time: Long)
    private val cache = object : LinkedHashMap<Key, Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Cached>) = size > 512
    }
    private val slots = Semaphore(3)

    /** Probe only the selected channel, not thousands of channels while browsing/importing. */
    suspend fun resolve(channel: IptvChannel, trust: Boolean, refresh: Boolean = false): List<IptvRoute> {
        if (refresh) synchronized(cache) { channel.streamUrls.forEach { cache.remove(Key(it, trust)) } }
        withTimeoutOrNull(8_000) {
            coroutineScope {
                channel.streamUrls.map { url -> async {
                    slots.withPermit {
                        if (cached(url, trust) == null) {
                            val result = probe.probe(url, trust)
                            synchronized(cache) { cache[Key(url, trust)] = Cached(result, System.currentTimeMillis()) }
                        }
                    }
                } }.awaitAll()
            }
        }
        return channel.streamUrls.flatMapIndexed { index, url ->
            val info = cached(url, trust)
            val available = when (info?.available) {
                true -> IptvRouteAvailability.AVAILABLE
                false -> IptvRouteAvailability.UNAVAILABLE
                null -> IptvRouteAvailability.UNKNOWN
            }
            if (info?.variants?.isNotEmpty() == true) info.variants.map {
                IptvRoute(url, index + 1, it.url, it.width, it.height, it.bitrate, available)
            } else listOf(IptvRoute(url, index + 1, width = info?.width ?: 0, height = info?.height ?: 0,
                bitrate = info?.bitrate ?: 0, availability = available))
        }
    }

    private fun cached(url: String, trust: Boolean): IptvProbeResult? = synchronized(cache) {
        cache[Key(url, trust)]?.takeIf {
            val hasResolution = it.result.width > 0 && it.result.height > 0 ||
                it.result.variants.any { variant -> variant.width > 0 && variant.height > 0 }
            val ttl = if (it.result.available == true && hasResolution) 21_600_000L else 60_000L
            System.currentTimeMillis() - it.time in 0L until ttl
        }?.result
    }
}
