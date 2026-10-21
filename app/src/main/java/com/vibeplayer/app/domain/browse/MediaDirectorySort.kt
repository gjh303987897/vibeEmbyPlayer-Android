package com.vibeplayer.app.domain.browse

import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.LocalMediaItem
import com.vibeplayer.app.model.WebDavItem
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

fun sortWebDavItems(items: List<WebDavItem>, order: BrowseSortOrder): List<WebDavItem> =
    sortDirectory(items, order, { it.path }, { it.name }, { it.isDirectory }, { it.size }) {
        parseWebDavModifiedTime(it.modifiedAt)
    }

fun sortLocalItems(items: List<LocalMediaItem>, order: BrowseSortOrder): List<LocalMediaItem> =
    sortDirectory(items, order, { it.uri }, { it.name }, { it.isDirectory }, { it.size }) {
        it.lastModified.takeIf { time -> time > 0 }
    }

/** DAV:getlastmodified is an HTTP date; also accept ISO timestamps used by some providers. */
internal fun parseWebDavModifiedTime(value: String): Long? {
    val text = value.trim()
    if (text.isEmpty()) return null
    return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        .getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}

private data class SortEntry<T>(val item: T, val id: String, val name: String, val foldedName: String,
    val directory: Boolean, val size: Long?, val modified: Long?)

/** Decorate once so neither case folding nor date parsing happens inside the comparator. */
private fun <T> sortDirectory(items: List<T>, order: BrowseSortOrder, id: (T) -> String,
    name: (T) -> String, directory: (T) -> Boolean, size: (T) -> Long, modified: (T) -> Long?): List<T> {
    val byTime = order == BrowseSortOrder.MODIFIED_NEWEST || order == BrowseSortOrder.MODIFIED_OLDEST
    val entries = items.map { item ->
        val label = name(item)
        SortEntry(item, id(item), label, label.lowercase(Locale.ROOT), directory(item),
            size(item).takeIf { it >= 0 }, if (byTime) modified(item) else null)
    }
    return entries.sortedWith { left, right ->
        // Descending order reverses the selected field, never the folder/file groups.
        val folders = right.directory.compareTo(left.directory)
        if (folders != 0) folders else {
            val primary = when (order) {
                BrowseSortOrder.NAME_ASC -> left.foldedName.compareTo(right.foldedName)
                BrowseSortOrder.NAME_DESC -> right.foldedName.compareTo(left.foldedName)
                BrowseSortOrder.SIZE_ASC -> compareKnown(left.size, right.size, descending = false)
                BrowseSortOrder.SIZE_DESC -> compareKnown(left.size, right.size, descending = true)
                BrowseSortOrder.MODIFIED_NEWEST -> compareKnown(left.modified, right.modified, descending = true)
                BrowseSortOrder.MODIFIED_OLDEST -> compareKnown(left.modified, right.modified, descending = false)
            }
            if (primary != 0) primary else compareValuesBy(left, right, { it.foldedName }, { it.name }, { it.id })
        }
    }.map { it.item }
}

/** Unavailable metadata remains last in both directions, with deterministic name ties. */
private fun compareKnown(left: Long?, right: Long?, descending: Boolean): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    descending -> right.compareTo(left)
    else -> left.compareTo(right)
}
