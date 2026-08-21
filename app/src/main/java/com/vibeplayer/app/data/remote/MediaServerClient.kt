package com.vibeplayer.app.data.remote

import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.model.MediaPerson
import com.vibeplayer.app.model.UserSession

/** One page of a paged item query. */
data class ItemPage(
    val items: List<MediaItem>,
    val total: Int? = null
)

/** Description of a prepared playback stream. */
data class PlaybackTarget(
    val url: String,
    val startSeconds: Double = 0.0,
    val mediaSourceId: String = "",
    val playSessionId: String = "",
    val subtitleStreamIndex: Int = -1
)

/**
 * Abstraction over an Emby or Jellyfin media server. Mirrors the Qt reference
 * MediaServiceClient. Return types are Result<...> for explicit error handling.
 */
interface MediaServerClient {

    /** Logs in with a username + password. */
    suspend fun login(
        server: com.vibeplayer.app.model.ServerConfig,
        username: String,
        password: String
    ): Result<UserSession>

    suspend fun fetchLibraries(session: UserSession): Result<List<MediaLibrary>>

    suspend fun fetchLibraryItems(
        session: UserSession,
        library: MediaLibrary,
        parentId: String,
        startIndex: Int,
        limit: Int
    ): Result<ItemPage>

    suspend fun searchItems(
        session: UserSession,
        searchTerm: String,
        startIndex: Int,
        limit: Int
    ): Result<ItemPage>

    suspend fun fetchContinueWatching(session: UserSession, limit: Int): Result<List<MediaItem>>

    suspend fun fetchSuggestedSeries(session: UserSession, limit: Int): Result<List<MediaItem>>

    suspend fun fetchSeriesSeasons(session: UserSession, seriesId: String): Result<List<MediaItem>>

    suspend fun fetchSeasonEpisodes(
        session: UserSession,
        seriesId: String,
        seasonId: String
    ): Result<List<MediaItem>>

    suspend fun fetchItemDetails(session: UserSession, itemId: String): Result<MediaItem>

    /** Fetches PlaybackInfo and builds a direct stream URL. */
    suspend fun fetchPlaybackUrl(session: UserSession, item: MediaItem): Result<PlaybackTarget>

    suspend fun reportPlaybackStart(session: UserSession, report: PlaybackReport)
    suspend fun reportPlaybackProgress(session: UserSession, report: PlaybackReport)
    suspend fun reportPlaybackStopped(session: UserSession, report: PlaybackReport)
}

data class PlaybackReport(
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String,
    val positionTicks: Long = 0L,
    val paused: Boolean = false
)
