package com.vibeplayer.app.data.remote

import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.model.UserSession
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emby REST API client.
 * Endpoints follow the Qt reference EmbyClient and the Emby REST API docs.
 */
@Singleton
class EmbyClient @Inject constructor(
    network: MediaNetworkClient,
    json: Json
) : MediaServerClientBase(network, json, authScheme = "Emby") {

    override fun librariesUrl(session: UserSession): String =
        makeUrl(session.server.baseUrl, "/Users/${session.userId}/Views")

    override fun libraryItemsUrl(
        session: UserSession,
        library: MediaLibrary,
        parentId: String,
        startIndex: Int,
        limit: Int
    ): String {
        val effectiveParentId = parentId.ifEmpty { library.id }
        val params = mutableListOf<Pair<String, Any?>>(
            "ParentId" to effectiveParentId,
            "Recursive" to "false",
            "StartIndex" to startIndex,
            "Limit" to limit,
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,SeriesPrimaryImageTag,ParentId",
            "EnableImages" to "true",
            "EnableUserData" to "true"
        )
        if (effectiveParentId == library.id && library.collectionType.equals("movies", ignoreCase = true)) {
            params.add("IncludeItemTypes" to "Movie")
        } else if (effectiveParentId == library.id && library.collectionType.equals("tvshows", ignoreCase = true)) {
            params.add("IncludeItemTypes" to "Series")
        }
        return makeUrl(session.server.baseUrl, "/Users/${session.userId}/Items") + query(*params.toTypedArray())
    }

    override fun searchUrl(session: UserSession, searchTerm: String, startIndex: Int, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/Users/${session.userId}/Items") + query(
            "SearchTerm" to searchTerm,
            "Recursive" to "true",
            "IncludeItemTypes" to "Movie,Series,Video",
            "StartIndex" to startIndex.coerceAtLeast(0),
            "Limit" to limit.coerceAtLeast(1),
            "SortBy" to "SortName",
            "SortOrder" to "Ascending",
            "Fields" to "PrimaryImageAspectRatio",
            "EnableImages" to "true",
            "ImageTypeLimit" to "1",
            "EnableImageTypes" to "Primary",
            "EnableUserData" to "true"
        )
    }

    override fun continueWatchingUrl(session: UserSession, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/Users/${session.userId}/Items") + query(
            "Recursive" to "true",
            "Filters" to "IsResumable",
            "IncludeItemTypes" to "Movie,Episode",
            "SortBy" to "DatePlayed",
            "SortOrder" to "Descending",
            "Limit" to limit,
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,SeriesPrimaryImageTag,ParentId",
            "EnableImages" to "true",
            "EnableUserData" to "true"
        )
    }

    override fun suggestedSeriesUrl(session: UserSession, limit: Int): String {
        return makeUrl(session.server.baseUrl, "/Users/${session.userId}/Suggestions") + query(
            "Recursive" to "true",
            "IncludeItemTypes" to "Series",
            "Limit" to limit.coerceAtLeast(1),
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,ParentId",
            "EnableImages" to "true",
            "ImageTypeLimit" to "2",
            "EnableImageTypes" to "Primary,Backdrop",
            "EnableUserData" to "true"
        )
    }

    override fun seasonsUrl(session: UserSession, seriesId: String): String {
        return makeUrl(session.server.baseUrl, "/Shows/$seriesId/Seasons") + query(
            "UserId" to session.userId,
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,SeriesPrimaryImageTag",
            "EnableImages" to "true",
            "EnableUserData" to "true"
        )
    }

    override fun episodesUrl(session: UserSession, seriesId: String, seasonId: String): String {
        return makeUrl(session.server.baseUrl, "/Shows/$seriesId/Episodes") + query(
            "UserId" to session.userId,
            "SeasonId" to seasonId,
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,SeriesPrimaryImageTag,ParentId",
            "EnableImages" to "true",
            "EnableImageTypes" to "Primary,Backdrop,Logo",
            "EnableUserData" to "true",
            "SortBy" to "SortName",
            "SortOrder" to "Ascending"
        )
    }

    override fun itemDetailsUrl(session: UserSession, itemId: String): String {
        return makeUrl(session.server.baseUrl, "/Users/${session.userId}/Items") + query(
            "Ids" to itemId,
            "Fields" to "PrimaryImageAspectRatio,Overview,Genres,People,DateCreated,RunTimeTicks,CommunityRating,OfficialRating,BackdropImageTags,SeriesPrimaryImageTag,ParentId",
            "EnableImages" to "true",
            "EnableImageTypes" to "Primary,Backdrop,Logo",
            "EnableUserData" to "true"
        )
    }

    override fun usesItemQueryForDetails(): Boolean = true
}
