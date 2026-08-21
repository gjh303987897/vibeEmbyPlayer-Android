package com.vibeplayer.app.data.remote.mapper

import com.vibeplayer.app.data.remote.dto.ItemDto
import com.vibeplayer.app.data.remote.dto.PersonDto
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import com.vibeplayer.app.model.MediaPerson

/**
 * Maps Emby/Jellyfin DTOs into domain models.
 * Mirrors the field mapping in the Qt reference MediaServerClientBase.
 */
object MediaDtoMapper {

    fun library(dto: ItemDto, baseUrl: String, token: String): MediaLibrary {
        val collectionType = dto.CollectionType ?: ""
        val itemType = dto.Type ?: ""
        return MediaLibrary(
            id = dto.itemId,
            name = dto.Name ?: "",
            collectionType = collectionType,
            itemType = collectionType.ifEmpty { itemType },
            imageTag = dto.primaryImageTagValue,
            imageUrl = primaryImageUrl(baseUrl, dto.itemId, dto.primaryImageTagValue, token, 420),
            childCount = dto.ChildCount ?: 0
        )
    }

    fun item(dto: ItemDto, baseUrl: String, token: String): MediaItem {
        val seriesId = dto.SeriesId
            ?: if (dto.Type.equals("Series", ignoreCase = true)) dto.itemId else ""
        val logoItemId = dto.logoTag.ifEmpty { null }?.let { dto.itemId }
            ?: dto.parentLogoItemId
        val logoTag = dto.logoTag.ifEmpty { dto.parentLogoImageTag ?: "" }

        val (backdropItemId, backdropTags) = backdropSource(dto)
        val backdrops = backdropTags.mapIndexedNotNull { index, tag ->
            backdropImageUrl(baseUrl, backdropItemId, tag, token, 1600, index)
        }

        val userData = dto.UserData
        val runTimeTicks = dto.RunTimeTicks ?: 0L

        return MediaItem(
            id = dto.itemId,
            parentId = dto.ParentId ?: "",
            name = dto.Name ?: "",
            itemType = dto.Type ?: "",
            isFolder = dto.IsFolder ?: false,
            productionYear = dto.ProductionYear?.toString() ?: "",
            seriesId = seriesId,
            seriesName = dto.SeriesName ?: "",
            seriesImageTag = dto.seriesPrimaryImageTag ?: "",
            seriesImageUrl = primaryImageUrl(
                baseUrl, seriesId, dto.seriesPrimaryImageTag ?: "", token, 460
            ),
            childCount = dto.ChildCount ?: 0,
            overview = dto.Overview ?: "",
            imageTag = dto.primaryImageTagValue,
            imageUrl = primaryImageUrl(baseUrl, dto.itemId, dto.primaryImageTagValue, token, 460),
            logoImageUrl = logoImageUrl(baseUrl, logoItemId ?: "", logoTag, token, 900),
            backdropImageUrl = backdrops.firstOrNull() ?: "",
            backdropImageUrls = backdrops,
            communityRating = (dto.CommunityRating ?: 0.0)
                .takeIf { it > 0 }?.let { "%.1f".format(it) } ?: "",
            officialRating = dto.OfficialRating ?: "",
            runTimeTicks = runTimeTicks,
            runTime = runtimeFromTicks(runTimeTicks),
            genres = (dto.Genres ?: emptyList())
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", "),
            peopleList = (dto.People ?: emptyList())
                .map { person(it, baseUrl, token) }
                .filter { it.name.isNotEmpty() },
            seasonName = dto.SeasonName ?: "",
            indexNumber = dto.IndexNumber?.let { if (it >= 0) it.toString() else "" } ?: "",
            parentIndexNumber = dto.ParentIndexNumber?.let { if (it >= 0) it.toString() else "" } ?: "",
            playbackPositionTicks = userData?.PlaybackPositionTicks ?: 0L,
            playedPercentage = userData?.PlayedPercentage ?: 0.0,
            played = userData?.Played ?: false
        )
    }

    fun person(dto: PersonDto, baseUrl: String, token: String): MediaPerson {
        val personImageTag = dto.PrimaryImageTag ?: dto.ImageTags?.get("Primary") ?: ""
        return MediaPerson(
            id = dto.Id ?: "",
            name = dto.Name ?: "",
            role = dto.Role ?: "",
            type = dto.Type ?: "",
            imageTag = personImageTag,
            imageUrl = primaryImageUrl(baseUrl, dto.Id ?: "", personImageTag, token, 320)
        )
    }

    fun peopleText(people: List<MediaPerson>): String =
        people.joinToString(", ") {
            if (it.role.isEmpty()) it.name else "${it.name} as ${it.role}"
        }

    private fun backdropSource(dto: ItemDto): Pair<String, List<String>> {
        val itemId = dto.itemId
        val itemType = dto.Type ?: ""
        val direct = dto.BackdropImageTags ?: emptyList()
        val parentItemId = dto.parentBackdropItemId ?: ""
        val parentTags = dto.parentBackdropImageTags ?: emptyList()

        return when {
            itemType.equals("Episode", ignoreCase = true) && parentItemId.isNotEmpty() &&
                parentTags.isNotEmpty() -> parentItemId to parentTags
            itemId.isNotEmpty() && direct.isNotEmpty() -> itemId to direct
            else -> parentItemId to parentTags
        }
    }

    private fun runtimeFromTicks(ticks: Long): String {
        if (ticks <= 0) return ""
        val minutes = ticks / 10_000_000 / 60
        return if (minutes <= 0) "" else "$minutes min"
    }

    private fun imageUrl(
        baseUrl: String,
        itemId: String,
        imageTag: String,
        token: String,
        width: Int,
        imageType: String = "Primary",
        index: Int = -1
    ): String {
        if (baseUrl.isEmpty() || itemId.isEmpty() || imageTag.isEmpty()) return ""
        val base = baseUrl.trim().trimEnd('/')
        val path = if (index >= 0) {
            "/Items/$itemId/Images/$imageType/$index"
        } else {
            "/Items/$itemId/Images/$imageType"
        }
        val query = buildQuery(width = width, tag = imageTag, token = token)
        return "$base$path$query"
    }

    private fun primaryImageUrl(
        baseUrl: String,
        itemId: String,
        imageTag: String,
        token: String,
        width: Int
    ): String = imageUrl(baseUrl, itemId, imageTag, token, width)

    private fun logoImageUrl(
        baseUrl: String,
        itemId: String,
        imageTag: String,
        token: String,
        width: Int
    ): String {
        if (baseUrl.isEmpty() || itemId.isEmpty() || imageTag.isEmpty()) return ""
        val base = baseUrl.trim().trimEnd('/')
        val query = buildQuery(width = width, tag = imageTag, token = token)
        return "$base/Items/$itemId/Images/Logo$query"
    }

    private fun backdropImageUrl(
        baseUrl: String,
        itemId: String,
        imageTag: String,
        token: String,
        width: Int,
        index: Int
    ): String = imageUrl(baseUrl, itemId, imageTag, token, width, "Backdrop", index)

    private fun buildQuery(width: Int, tag: String, token: String): String {
        val sb = StringBuilder("?maxWidth=$width&quality=90")
        if (tag.isNotEmpty()) sb.appendQueryItem("tag", tag)
        if (token.isNotEmpty()) sb.appendQueryItem("api_key", token)
        return sb.toString()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun StringBuilder.appendQueryItem(key: String, value: String) {
        if (isNotEmpty()) append("&")
        append(key).append("=").append(encode(value))
    }
}
