package com.vibeplayer.app.data.remote

import android.util.Log
import com.vibeplayer.app.data.remote.dto.ItemQueryResult
import com.vibeplayer.app.data.remote.dto.LibraryQueryResult
import com.vibeplayer.app.data.remote.dto.MediaSourceDto
import com.vibeplayer.app.data.remote.dto.MediaStreamDto
import com.vibeplayer.app.data.remote.dto.PlaybackInfoResponse
import com.vibeplayer.app.data.remote.mapper.MediaDtoMapper
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.UserSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * Shared implementation for Emby and Jellyfin clients.
 * Handles base URL normalization, JSON parsing, image/stream URL building and
 * playback reporting; subclasses provide framework-specific endpoints and the
 * authentication scheme.
 */
abstract class MediaServerClientBase(
    protected val network: MediaNetworkClient,
    protected val json: Json,
    protected val authScheme: String
) : MediaServerClient {

    override suspend fun login(
        server: ServerConfig,
        username: String,
        password: String
    ): Result<UserSession> {
        val url = makeUrl(server.baseUrl, "/Users/AuthenticateByName")
        val headers = EmbyAuth.headers(server.serviceType)
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }
        val result = network.postJson(
            url, headers, body.toString(),
            trustSelfSigned = server.trustSelfSignedCertificate
        )
            val root = result.parseJsonObject() ?: return Result.failure(result.asThrowable())
        return runCatching {
            val user = root["User"]?.jsonObject
            val token = root["AccessToken"]?.jsonPrimitive?.contentOrNull ?: ""
            val userId = user?.get("Id")?.jsonPrimitive?.contentOrNull ?: ""
            val name = user?.get("Name")?.jsonPrimitive?.contentOrNull ?: ""
            if (token.isEmpty() || userId.isEmpty()) {
                throw NetworkException(NetworkErrorKind.PARSE, "Authentication response is missing token or user id")
            }
            UserSession(
                server = server,
                userId = userId,
                username = name.ifEmpty { "${server.serviceType.displayName} User" },
                accessToken = token
            )
        }
    }

    override suspend fun fetchLibraries(session: UserSession): Result<List<MediaLibrary>> {
        val result = network.get(
            librariesUrl(session),
            EmbyAuth.headers(session.server.serviceType, session.accessToken),
            trustSelfSigned = session.server.trustSelfSignedCertificate
        )
        val body = result.parseBytes() ?: return Result.failure(result.asThrowable())
        return runCatching {
            val parsed = json.decodeFromString<LibraryQueryResult>(body)
            parsed.Items
                .filter { it.itemId.isNotEmpty() }
                .map { MediaDtoMapper.library(it, session.server.baseUrl, session.accessToken) }
        }
    }

    override suspend fun fetchLibraryItems(
        session: UserSession,
        library: MediaLibrary,
        parentId: String,
        startIndex: Int,
        limit: Int
    ): Result<ItemPage> {
        val url = libraryItemsUrl(session, library, parentId, startIndex, limit)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val result = network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate)
        return parseItemPage(result, session)
    }

    override suspend fun searchItems(
        session: UserSession,
        searchTerm: String,
        startIndex: Int,
        limit: Int
    ): Result<ItemPage> {
        val normalized = searchTerm.trim()
        if (normalized.isEmpty()) {
            return Result.failure(NetworkException(NetworkErrorKind.INVALID_URL, "Search term is required"))
        }
        val url = searchUrl(session, normalized, startIndex, limit)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        return parseItemPage(network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate), session)
    }

    override suspend fun fetchContinueWatching(
        session: UserSession,
        limit: Int
    ): Result<List<MediaItem>> {
        val url = continueWatchingUrl(session, limit)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val page = parseItemPage(network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate), session)
        return page.map { keepLatestContinueItems(it.items) }
    }

    override suspend fun fetchSuggestedSeries(
        session: UserSession,
        limit: Int
    ): Result<List<MediaItem>> {
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val page = parseItemPage(
            network.get(suggestedSeriesUrl(session, limit), headers, trustSelfSigned = session.server.trustSelfSignedCertificate),
            session
        )
        val series = page.map { it.items.filter { item -> item.itemType.equals("Series", ignoreCase = true) } }
        // Some Emby-compatible servers return Studio / Genre entries from
        // /Suggestions even when IncludeItemTypes=Series is present, so the
        // Series-only filter can render nothing. To keep recommended series on
        // the home (matching the desktop client), fall back to a random Series
        // query from the user item root when the primary response has none.
        if (series.getOrDefault(emptyList()).isEmpty()) {
            val fallbackUrl = suggestedSeriesFallbackUrl(session, limit)
            if (fallbackUrl != null) {
                val fallback = parseItemPage(
                    network.get(fallbackUrl, headers, trustSelfSigned = session.server.trustSelfSignedCertificate),
                    session
                )
                return fallback.map { it.items.filter { item -> item.itemType.equals("Series", ignoreCase = true) } }
            }
        }
        return series
    }

    override suspend fun fetchSeriesSeasons(
        session: UserSession,
        seriesId: String
    ): Result<List<MediaItem>> {
        if (seriesId.isEmpty()) {
            return Result.failure(NetworkException(NetworkErrorKind.INVALID_URL, "Series id is required"))
        }
        val url = seasonsUrl(session, seriesId)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        return parseItemPage(network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate), session).map { it.items }
    }

    override suspend fun fetchSeasonEpisodes(
        session: UserSession,
        seriesId: String,
        seasonId: String
    ): Result<List<MediaItem>> {
        if (seriesId.isEmpty() || seasonId.isEmpty()) {
            return Result.failure(NetworkException(NetworkErrorKind.INVALID_URL, "Series and season ids are required"))
        }
        val url = episodesUrl(session, seriesId, seasonId)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        return parseItemPage(network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate), session).map { it.items }
    }

    override suspend fun fetchItemDetails(
        session: UserSession,
        itemId: String
    ): Result<com.vibeplayer.app.model.MediaItem> {
        val url = itemDetailsUrl(session, itemId)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val result = network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate)

        return if (usesItemQueryForDetails()) {
            val page = parseItemPage(result, session)
            page.map { pageResult ->
                pageResult.items.firstOrNull()
                    ?: throw NetworkException(NetworkErrorKind.PARSE, "Media item was not found")
            }
        } else {
            val body = result.parseBytes() ?: return Result.failure(result.asThrowable())
            runCatching {
                val dto = json.decodeFromString<com.vibeplayer.app.data.remote.dto.ItemDto>(body)
                MediaDtoMapper.item(dto, session.server.baseUrl, session.accessToken)
            }
        }
    }

    override suspend fun fetchPlaybackUrl(
        session: UserSession,
        item: com.vibeplayer.app.model.MediaItem
    ): Result<PlaybackTarget> {
        if (session.server.baseUrl.isEmpty() || item.id.isEmpty() || session.accessToken.isEmpty()) {
            return Result.failure(NetworkException(NetworkErrorKind.INVALID_URL, "Playback URL cannot be created"))
        }
        val url = makeUrl(session.server.baseUrl, "/Items/${item.id}/PlaybackInfo")
            .plus("?UserId=${session.userId}&IsPlayback=true&AutoOpenLiveStream=true")
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val result = network.get(url, headers, trustSelfSigned = session.server.trustSelfSignedCertificate)
        val body = result.parseBytes() ?: return Result.failure(result.asThrowable())
        return runCatching {
            val info = json.decodeFromString<PlaybackInfoResponse>(body)
            val source = info.MediaSources?.firstOrNull()
                ?: throw NetworkException(NetworkErrorKind.PARSE, "Playback info did not include a media source")
            val mediaSourceId = source.sourceId
            val playSessionId = info.PlaySessionId ?: ""
            if (mediaSourceId.isEmpty() || playSessionId.isEmpty()) {
                throw NetworkException(NetworkErrorKind.PARSE, "Playback info is missing MediaSourceId or PlaySessionId")
            }
            streamUrl(session, item, source, playSessionId)
        }
    }

    override suspend fun reportPlaybackStart(session: UserSession, report: PlaybackReport) {
        postPlaybackReport(session, "/Sessions/Playing", report)
    }

    override suspend fun reportPlaybackProgress(session: UserSession, report: PlaybackReport) {
        postPlaybackReport(session, "/Sessions/Playing/Progress", report)
    }

    override suspend fun reportPlaybackStopped(session: UserSession, report: PlaybackReport) {
        postPlaybackReport(session, "/Sessions/Playing/Stopped", report)
    }

    /* ---- helpers ---- */

    private suspend fun parseItemPage(result: NetworkResult, session: UserSession): Result<ItemPage> {
        val body = result.parseBytes() ?: return Result.failure(result.asThrowable())
        return runCatching {
            val parsed = json.decodeFromString<ItemQueryResult>(body)
            ItemPage(
                items = parsed.Items
                    .filter { it.itemId.isNotEmpty() }
                    .map { MediaDtoMapper.item(it, session.server.baseUrl, session.accessToken) },
                total = parsed.TotalRecordCount
            )
        }
    }

    private suspend fun postPlaybackReport(session: UserSession, path: String, report: PlaybackReport) {
        if (session.server.baseUrl.isEmpty() || session.accessToken.isEmpty() || report.itemId.isEmpty()) {
            return
        }
        val url = makeUrl(session.server.baseUrl, path)
        val headers = EmbyAuth.headers(session.server.serviceType, session.accessToken)
        val body = buildJsonObject {
            put("ItemId", report.itemId)
            put("MediaSourceId", report.mediaSourceId)
            put("PlaySessionId", report.playSessionId)
            put("CanSeek", true)
            put("PlayMethod", "DirectPlay")
            put("PositionTicks", maxOf(0L, report.positionTicks))
            put("IsPaused", report.paused)
        }
        network.postJson(
            url, headers, body.toString(),
            trustSelfSigned = session.server.trustSelfSignedCertificate
        )
    }

    private fun streamUrl(
        session: UserSession,
        item: com.vibeplayer.app.model.MediaItem,
        source: MediaSourceDto,
        playSessionId: String
    ): PlaybackTarget {
        val mediaSourceId = source.sourceId
        val base = makeUrl(session.server.baseUrl, "/Videos/${item.id}/stream")
        val params = linkedMapOf<String, Any?>(
            "MediaSourceId" to mediaSourceId,
            "PlaySessionId" to playSessionId,
            "api_key" to session.accessToken,
            "DeviceId" to EmbyAuth.DEVICE_ID
        )
        // Static = the untouched original file. Only valid while the device can
        // decode the audio track the player will pick: when it cannot, Media3
        // silently drops that track and the title plays without any sound and
        // without ever raising an error. Letting the server re-encode just the
        // audio (video is stream-copied) keeps the picture untouched and gives a
        // codec every phone can play.
        val streams = source.MediaStreams.orEmpty()
        val audioCodec = AudioPlaybackCapability.playableAudioCodec(streams)
        when (AudioPlaybackCapability.planFor(streams)) {
            AudioStreamPlan.DIRECT -> params["static"] = true
            AudioStreamPlan.TRANSCODE_AUDIO -> {
                Log.i(TAG, "audio codec '$audioCodec' is not decodable here, asking the server to transcode audio")
                params["static"] = false
                params["Context"] = "Streaming"
                params["EnableAutoStreamCopy"] = true
                params["AllowVideoStreamCopy"] = true
                params["AllowAudioStreamCopy"] = false
                params["AudioCodec"] = FALLBACK_AUDIO_CODEC
                params["MaxAudioChannels"] = FALLBACK_AUDIO_CHANNELS
            }
        }
        val subtitleStreamIndex = selectSubtitleStream(source)
        if (subtitleStreamIndex >= 0) {
            params["EnableSubtitles"] = true
            params["SubtitleStreamIndex"] = subtitleStreamIndex
        }
        val url = base + query(*params.toList().toTypedArray())
        return PlaybackTarget(
            url = url,
            startSeconds = item.playbackPositionSeconds,
            mediaSourceId = mediaSourceId,
            playSessionId = playSessionId,
            subtitleStreamIndex = selectSubtitleStream(source)
        )
    }

    private fun selectSubtitleStream(source: MediaSourceDto): Int {
        val serverDefault = source.defaultSubtitleStreamIndex ?: -1
        val streams = source.MediaStreams ?: emptyList()

        data class Sub(val index: Int, val isDefault: Boolean, val isForced: Boolean)

        val subtitles = streams.mapNotNull { s ->
            if (!s.Type.equals("Subtitle", ignoreCase = true)) return@mapNotNull null
            val index = s.Index ?: -1
            if (index < 0 || (s.IsExternal ?: false)) return@mapNotNull null
            Sub(index, s.IsDefault ?: false, s.IsForced ?: false)
        }

        val serverSelected = subtitles.firstOrNull { it.index == serverDefault }
        if (serverSelected != null && !serverSelected.isForced) return serverSelected.index

        val fullDefault = subtitles.firstOrNull { it.isDefault && !it.isForced }
        return fullDefault?.index ?: serverSelected?.index ?: -1
    }

    private fun keepLatestContinueItems(items: List<com.vibeplayer.app.model.MediaItem>): List<com.vibeplayer.app.model.MediaItem> {
        val seenSeries = mutableSetOf<String>()
        val result = mutableListOf<com.vibeplayer.app.model.MediaItem>()
        for (item in items) {
            if (item.itemType.equals("Episode", ignoreCase = true)) {
                val seriesKey = item.seriesId.ifEmpty { item.seriesName.trim().lowercase() }
                if (seriesKey.isNotEmpty() && !seenSeries.add(seriesKey)) continue
            }
            result.add(item)
        }
        return result
    }

    /* ---- framework-specific endpoints ---- */

    protected abstract fun librariesUrl(session: UserSession): String

    protected abstract fun libraryItemsUrl(
        session: UserSession,
        library: com.vibeplayer.app.model.MediaLibrary,
        parentId: String,
        startIndex: Int,
        limit: Int
    ): String

    protected abstract fun searchUrl(session: UserSession, searchTerm: String, startIndex: Int, limit: Int): String

    protected abstract fun continueWatchingUrl(session: UserSession, limit: Int): String

    protected abstract fun suggestedSeriesUrl(session: UserSession, limit: Int): String

    /**
     * Optional fallback data source for recommended series, used when the primary
     * suggestions response yields no Series items. Return null to disable.
     * Only Emby provides a fallback today (matches the desktop reference client).
     */
    protected open fun suggestedSeriesFallbackUrl(session: UserSession, limit: Int): String? = null

    protected abstract fun seasonsUrl(session: UserSession, seriesId: String): String

    protected abstract fun episodesUrl(session: UserSession, seriesId: String, seasonId: String): String

    protected abstract fun itemDetailsUrl(session: UserSession, itemId: String): String

    /** True if item details come from a Users/{id}/Items query; false for /Items/{id}. */
    protected open fun usesItemQueryForDetails(): Boolean = true

    /* ---- url helpers ---- */

    protected fun makeUrl(baseUrl: String, path: String): String {
        // Normalize a bare host / LAN address (e.g. `192.168.1.5:8096` or
        // `emby.bangumi.ca`) to an http(s) URL before building the request.
        // Without this, a scheme-less base URL passed to OkHttp's
        // `Request.Builder.url()` throws IllegalArgumentException, which the
        // caller surfaces as a misleading "Invalid server URL" even though the
        // address itself is fine. addServer/editServer normalize on save, but a
        // stored value from an older build may already be scheme-less, so the
        // safe single chokepoint for every request URL is here. An empty base
        // is left untouched so callers that guard on it behave as before.
        var base = baseUrl.trim().trimEnd('/')
        if (base.isNotEmpty() && !base.contains("://")) {
            base = "http://$base"
        }
        return base + path
    }

    companion object {
        private const val TAG = "MediaServerClient"

        /**
         * Audio fallback the server encodes to when this device cannot decode the
         * original track. AAC is decodable on every Android device, and two
         * channels covers the downmix phones expect; the video stream is copied,
         * so this costs the server an audio-only re-encode.
         */
        private const val FALLBACK_AUDIO_CODEC = "aac"
        private const val FALLBACK_AUDIO_CHANNELS = 2
    }

    protected fun query(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder()
        for ((key, value) in pairs) {
            if (value == null) continue
            if (sb.isNotEmpty()) sb.append("&")
            sb.append(key).append("=").append(java.net.URLEncoder.encode(value.toString(), "UTF-8"))
        }
        return if (sb.isEmpty()) "" else "?$sb"
    }
}

/** Internal exception to carry a typed network error through runCatching. */
class NetworkException(
    val kind: NetworkErrorKind,
    message: String,
    val statusCode: Int = -1,
    cause: Throwable? = null
) : IOException(message, cause)

/** Convenience parsers shared by all clients. */
internal fun NetworkResult.parseBytes(): String? =
    (this as? NetworkResult.Success)?.body?.toString(Charsets.UTF_8)

internal fun NetworkResult.parseJsonObject(): JsonObject? {
    val body = (this as? NetworkResult.Success)?.body ?: return null
    return runCatching {
        Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
    }.getOrNull()
}

internal fun NetworkResult.errorOrNull(): NetworkError? =
    (this as? NetworkResult.Failure)?.error

/** Converts a failed [NetworkResult] into a [Throwable] for Result.failure. */
internal fun NetworkResult.asThrowable(): Throwable =
    errorOrNull()?.let { NetworkException(it.kind, it.message, it.statusCode, it.cause) }
        ?: NetworkException(NetworkErrorKind.UNKNOWN, "Unknown network error")
