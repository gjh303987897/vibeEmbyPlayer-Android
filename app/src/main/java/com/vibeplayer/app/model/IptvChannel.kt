package com.vibeplayer.app.model

import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import java.util.Locale

/** A channel owns ordered routes; persisted route IDs remain valid for old favourites/history. */
data class IptvChannel(val sources: List<IptvChannelEntity>) {
    private val first get() = sources.first()
    val id get() = first.id
    val playlistId get() = first.playlistId
    val name get() = first.name
    val groupName get() = first.groupName
    val logoUrl get() = sources.firstOrNull { it.logoUrl.isNotBlank() }?.logoUrl.orEmpty()
    val streamUrl get() = first.streamUrl
    val sourceIds get() = sources.map { it.id }.toSet()
    val streamUrls get() = sources.map { it.streamUrl }.distinct()
    fun isFavourite(ids: Set<String>) = sources.any { it.id in ids }
}

/** Match Qt's identity rule; never guess that HD/4K suffixes or different regional groups match. */
fun mergeIptvChannels(rows: List<IptvChannelEntity>): List<IptvChannel> {
    fun normalise(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    return rows.sortedBy { it.sortOrder }.groupBy {
        val identity = if (it.tvgId.isNotBlank()) "id:${it.tvgId.trim()}"
            else "name:${normalise(it.name)}\u0000${normalise(it.groupName)}"
        it.playlistId to identity
    }.values.map { IptvChannel(it) }
}
