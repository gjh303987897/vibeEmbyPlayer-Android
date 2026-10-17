package com.vibeplayer.app.data.remote.iptv

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.player.HlsVariant
import com.vibeplayer.app.player.hlsAttributes
import com.vibeplayer.app.player.parseHlsVariants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IptvProbeResult(val available: Boolean? = null, val variants: List<HlsVariant> = emptyList(),
    val width: Int = 0, val height: Int = 0, val bitrate: Long = 0)

/** HTTP is bounded/cancellable; platform extraction only sees bounded local samples, never a URL. */
@Singleton
class IptvQualityProbe internal constructor(
    factory: OkHttpClientFactory,
    private val inspect: (ByteArray) -> IptvProbeResult
) {
    @Inject constructor(@ApplicationContext context: Context, factory: OkHttpClientFactory) :
        this(factory, { extractIptvSample(context, it) })

    private val clients = listOf(false, true).associateWith { trust ->
        factory.client(trust).newBuilder().callTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    }
    private data class Sample(val bytes: ByteArray, val base: HttpUrl, val complete: Boolean)

    suspend fun probe(url: String, trustSelfSigned: Boolean): IptvProbeResult = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext IptvProbeResult()
        try {
            val sample = fetch(httpUrl, trustSelfSigned)
            val decoded = sample.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n')
            val text = if (sample.complete) decoded else decoded.substringBeforeLast('\n', "")
            if (!text.startsWith("#EXTM3U")) return@withContext inspect(sample.bytes).copy(available = true)
            val variants = parseHlsVariants(text, sample.base)
            if (variants.isNotEmpty()) return@withContext IptvProbeResult(true, variants)
            // A playable encrypted playlist is not an unavailable route just because it cannot be sampled.
            if (text.lineSequence().any { it.startsWith("#EXT-X-KEY:") && hlsAttributes(it)["METHOD"] != "NONE" })
                return@withContext IptvProbeResult(available = true)
            if (text.contains("#EXT-X-BYTERANGE") || text.contains("BYTERANGE="))
                return@withContext IptvProbeResult(available = true)
            val segment = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
                ?.let { sample.base.resolve(it) } ?: return@withContext IptvProbeResult(available = true)
            val init = text.lineSequence().firstOrNull { it.startsWith("#EXT-X-MAP:") }
                ?.let { hlsAttributes(it)["URI"] }?.let { sample.base.resolve(it) }
            val initBytes = init?.let { fetch(it, trustSelfSigned).bytes } ?: byteArrayOf()
            try { inspect(initBytes + fetch(segment, trustSelfSigned).bytes).copy(available = true) }
            catch (_: IOException) { IptvProbeResult(available = true) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            IptvProbeResult(available = false)
        } catch (_: Exception) {
            IptvProbeResult()
        }
    }

    private class RangeRejected : IOException()

    private suspend fun fetch(url: HttpUrl, trust: Boolean): Sample =
        try { fetchSample(url, trust, range = true) }
        catch (_: RangeRejected) { fetchSample(url, trust, range = false) }

    private suspend fun fetchSample(url: HttpUrl, trust: Boolean, range: Boolean): Sample = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).apply {
            if (range) header("Range", "bytes=0-524287")
        }.build()
        val call = clients.getValue(trust).newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (it.code == 416 && range) throw RangeRejected()
                        if (!it.isSuccessful) throw IOException("IPTV probe HTTP failure")
                        val source = it.body?.source() ?: throw IOException("Empty IPTV response")
                        val buffer = okio.Buffer()
                        var complete = false
                        try {
                            while (continuation.isActive && buffer.size < 524_288) {
                                if (source.read(buffer, minOf(16_384, 524_288 - buffer.size)) == -1L) {
                                    complete = true
                                    break
                                }
                            }
                        } catch (timeout: IOException) {
                            // A live low-bitrate source may never fill the byte limit before the
                            // deadline. Its partial sample can already contain the video metadata.
                            if (buffer.size == 0L) throw timeout
                        }
                        val result = Sample(buffer.readByteArray(), it.request.url, complete)
                        if (continuation.isActive) continuation.resume(result)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

}

private fun extractIptvSample(context: Context, bytes: ByteArray): IptvProbeResult {
    val file = File.createTempFile("iptv-probe-", ".sample", context.cacheDir)
    val extractor = MediaExtractor()
    try {
        file.writeBytes(bytes)
        FileInputStream(file).use { extractor.setDataSource(it.fd) }
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            if (!format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) continue
            fun integer(key: String) = if (format.containsKey(key)) format.getInteger(key).coerceAtLeast(0) else 0
            return IptvProbeResult(width = integer(MediaFormat.KEY_WIDTH), height = integer(MediaFormat.KEY_HEIGHT),
                bitrate = integer(MediaFormat.KEY_BIT_RATE).toLong())
        }
    } catch (_: Exception) {
        // Unsupported/truncated metadata means unknown quality, not an unplayable stream.
    } finally {
        extractor.release()
        file.delete()
    }
    return IptvProbeResult()
}
