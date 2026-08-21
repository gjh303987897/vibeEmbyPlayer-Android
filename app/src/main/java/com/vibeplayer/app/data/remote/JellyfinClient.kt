package com.vibeplayer.app.data.remote

import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.model.UserSession
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jellyfin REST API client.
 * Endpoints follow the Jellyfin OpenAPI stable spec and the Qt reference
 * JellyfinClient. Uses the `MediaBrowser` auth scheme.
 */
@Singleton
class JellyfinClient @Inject constructor(
    network: MediaNetworkClient,
    json: Json
) : MediaServerClientBase(network, json, authScheme = "MediaBrowser") {

    override fun librariesUrl(session: UserSession): String {
        return makeUrl(session.server.baseUrl, "/UserViews") + query(
            "userId" to session.userId,
            "includeHidden" to "false",
            "includeExternalContent" to "false"
        )
    }

    override fun libraryItemsUrl(
        session: UserSession,
        library: MediaLibrary,
        parentId: String,
        startIndex: Int,
        limit: Int
    ): String {
        val effectiveParentId = parentId.ifEmpty { library.id }
        val params = mutableListOf<Pair<String, Any?>>(
            "userId" to session.userId,
            "parentId" to effectiveParentId,
            "recursive" to "false",
            "startIndex" to startIndex,
            "limit" to limit,
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,SeriesPrimaryImageTag,ParentId",
            "enableImages" to "true",
            "enableUserData" to "true"
        )
        if (effectiveParentId == library.id && library.collectionType.equals("movies", ignoreCase = true)) {
            params.add("includeItemTypes" to "Movie")
        } else if (effectiveParentId == library.id && library.collectionType.equals("tvshows", ignoreCase = true)) {
            params.add("includeItemTypes" to "Series")
        }
        return makeUrl(session.server.baseUrl, "/Items") + query(*params.toTypedArray())
    }

    override fun searchUrl(session: UserSession, searchTerm: String, startIndex: Int, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/Items") + query(
            "userId" to session.userId,
            "searchTerm" to searchTerm,
            "recursive" to "true",
            "includeItemTypes" to "Movie,Series,Episode,Video",
            "startIndex" to startIndex.coerceAtLeast(0),
            "limit" to limit.coerceAtLeast(1),
            "sortBy" to "SortName",
            "sortOrder" to "Ascending",
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,SeriesPrimaryImageTag,ParentId",
            "enableImages" to "true",
            "enableUserData" to "true"
        )
    }

    override fun continueWatchingUrl(session: UserSession, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/UserItems/Resume") + query(
            "userId" to session.userId,
            "limit" to limit,
            "includeItemTypes" to "Movie,Episode",
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,SeriesPrimaryImageTag,ParentId",
            "enableImages" to "true",
            "enableUserData" to "true"
        )
    }

    override fun suggestedSeriesUrl(session: UserSession, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/Items/Suggestions") + query(
            "userId" to session.userId,
            "type" to "Series",
            "startIndex" to "0",
            "limit" to limit.coerceAtLeast(1),
            "enableTotalRecordCount" to "false"
        )
    }

    override fun seasonsUrl(session: UserSession, seriesId: String): String {
        return makeUrl(session.server.baseUrl, "/Shows/$seriesId/Seasons") + query(
            "userId" to session.userId,
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,SeriesPrimaryImageTag",
            "enableImages" to "true",
            "enableUserData" to "true"
        )
    }

    override fun episodesUrl(session: UserSession, seriesId: String, seasonId: String): String {
        return makeUrl(session.server.baseUrl, "/Shows/$seriesId/Episodes") + query(
            "userId" to session.userId,
            "seasonId" to seasonId,
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,SeriesPrimaryImageTag,ParentId",
            "enableImages" to "true",
            "enableUserData" to "true",
            "sortBy" to "SortName"
        )
    }

    override fun itemDetailsUrl(session: UserSession, itemId: String): String {
        return makeUrl(session.server.baseUrl, "/Items/$itemId") + query(
            "userId" to session.userId,
            "fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,SeriesPrimaryImageTag,ParentId"
        )
    }

    override fun usesItemQueryForDetails(): Boolean = false
}
