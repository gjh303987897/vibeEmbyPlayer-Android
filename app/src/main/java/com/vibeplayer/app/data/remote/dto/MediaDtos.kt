package com.vibeplayer.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Standard Emby/Jellyfin paged item query result envelope. */
@Serializable
data class ItemQueryResult(
    val Items: List<ItemDto> = emptyList(),
    val TotalRecordCount: Int? = null
)

/** Response envelope for user views / libraries. */
@Serializable
data class LibraryQueryResult(
    val Items: List<ItemDto> = emptyList()
)

/** Login response from /Users/AuthenticateByName. */
@Serializable
data class LoginResponse(
    val User: UserDto? = null,
    val AccessToken: String? = null
)

@Serializable
data class UserDto(
    val Id: String? = null,
    val Name: String? = null
)

/** A single media item (BaseItemDto subset used by the app). */
@Serializable
data class ItemDto(
    val Id: String? = null,
    val ParentId: String? = null,
    val Name: String? = null,
    val Type: String? = null,
    val IsFolder: Boolean? = null,
    val CollectionType: String? = null,
    val ProductionYear: Int? = null,
    val SeriesId: String? = null,
    val SeriesName: String? = null,
    @SerialName("SeriesPrimaryImageTag")
    val seriesPrimaryImageTag: String? = null,
    val ChildCount: Int? = null,
    val Overview: String? = null,
    val RunTimeTicks: Long? = null,
    val CommunityRating: Double? = null,
    val OfficialRating: String? = null,
    val Genres: List<String>? = null,
    val People: List<PersonDto>? = null,
    val SeasonName: String? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    @SerialName("PrimaryImageTag")
    val primaryImageTag: String? = null,
    val ImageTags: Map<String, String>? = null,
    val BackdropImageTags: List<String>? = null,
    @SerialName("ParentBackdropItemId")
    val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags")
    val parentBackdropImageTags: List<String>? = null,
    @SerialName("ParentLogoItemId")
    val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag")
    val parentLogoImageTag: String? = null,
    val UserData: UserDataDto? = null
) {
    val primaryImageTagValue: String
        get() = when {
            !primaryImageTag.isNullOrEmpty() -> primaryImageTag!!
            ImageTags?.get("Primary") != null -> ImageTags!!["Primary"]!!
            else -> ""
        }

    val logoTag: String
        get() = ImageTags?.get("Logo") ?: ""

    val itemId: String get() = Id ?: ""
}

@Serializable
data class PersonDto(
    val Id: String? = null,
    val Name: String? = null,
    val Role: String? = null,
    val Type: String? = null,
    val PrimaryImageTag: String? = null,
    val ImageTags: Map<String, String>? = null
)

@Serializable
data class UserDataDto(
    val Played: Boolean? = null,
    val PlaybackPositionTicks: Long? = null,
    val PlayedPercentage: Double? = null
)

/** PlaybackInfo response. */
@Serializable
data class PlaybackInfoResponse(
    val PlaySessionId: String? = null,
    val MediaSources: List<MediaSourceDto>? = null
)

@Serializable
data class MediaSourceDto(
    val Id: String? = null,
    @SerialName("DefaultSubtitleStreamIndex")
    val defaultSubtitleStreamIndex: Int? = null,
    val MediaStreams: List<MediaStreamDto>? = null
) {
    val sourceId: String get() = Id ?: ""
}

@Serializable
data class MediaStreamDto(
    val Type: String? = null,
    val Index: Int? = null,
    val IsDefault: Boolean? = null,
    val IsForced: Boolean? = null,
    val IsExternal: Boolean? = null
)
