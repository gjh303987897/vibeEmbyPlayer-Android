package com.vibeplayer.app.domain.browse

import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.LocalMediaItem
import com.vibeplayer.app.model.WebDavItem
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class MediaDirectorySortTest {
    private val files = listOf(
        LocalMediaItem("beta.mkv", "b", false, 30, 300),
        LocalMediaItem("ALPHA.mkv", "a", false, 10, 100),
        LocalMediaItem("charlie.mkv", "c", false, 20, 200)
    )
    private val expected = mapOf(
        BrowseSortOrder.NAME_ASC to listOf("a", "b", "c"),
        BrowseSortOrder.NAME_DESC to listOf("c", "b", "a"),
        BrowseSortOrder.SIZE_ASC to listOf("a", "c", "b"),
        BrowseSortOrder.SIZE_DESC to listOf("b", "c", "a"),
        BrowseSortOrder.MODIFIED_NEWEST to listOf("b", "c", "a"),
        BrowseSortOrder.MODIFIED_OLDEST to listOf("a", "c", "b")
    )

    @Test fun allSixLocalOrdersUseTheSelectedFieldAndAlwaysKeepFoldersFirst() {
        val folder = LocalMediaItem("zzz folder", "folder", true, Long.MAX_VALUE, 1)
        for ((order, ids) in expected) {
            val sorted = sortLocalItems(files + folder, order)
            assertEquals("folders first in $order", "folder", sorted.first().uri)
            assertEquals(order.name, ids, sorted.drop(1).map { it.uri })
            assertEquals(4, sorted.size)
        }
    }

    @Test fun webdavUsesTheSameSixOrdersIncludingParsedDates() {
        val items = files.map { WebDavItem(it.name, it.uri, false, it.size,
            modifiedAt = Instant.ofEpochMilli(it.lastModified * 1000).toString()) }
        val folder = WebDavItem("zzz folder", "folder", true)
        for ((order, ids) in expected) {
            val sorted = sortWebDavItems(items + folder, order)
            assertEquals("folder", sorted.first().path)
            assertEquals(order.name, ids, sorted.drop(1).map { it.path })
        }
    }

    @Test fun httpDatesAreChronologicalInsteadOfLexicographicAndRespectOffsets() {
        assertEquals(Instant.parse("2015-10-21T07:28:00Z").toEpochMilli(),
            parseWebDavModifiedTime("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(parseWebDavModifiedTime("Wed, 21 Oct 2015 07:28:00 GMT"),
            parseWebDavModifiedTime("2015-10-21T09:28:00+02:00"))
        val items = listOf(
            WebDavItem("new", "new", false, modifiedAt = "Wed, 02 Jan 2019 12:00:00 GMT"),
            WebDavItem("old", "old", false, modifiedAt = "Mon, 31 Dec 2018 12:00:00 GMT"),
            WebDavItem("offset", "offset", false, modifiedAt = "2019-01-02T12:30:00+02:00")
        )
        assertEquals(listOf("new", "offset", "old"), sortWebDavItems(items, BrowseSortOrder.MODIFIED_NEWEST).map { it.path })
        assertEquals(listOf("old", "offset", "new"), sortWebDavItems(items, BrowseSortOrder.MODIFIED_OLDEST).map { it.path })
    }

    @Test fun missingOrInvalidDatesAreLastInBothDirectionsWithoutDroppingRows() {
        for (value in listOf("", "not-a-date", "Wed, 35 Oct 2015 07:28:00 GMT")) assertNull(parseWebDavModifiedTime(value))
        val items = listOf(WebDavItem("a", "missing", false),
            WebDavItem("b", "invalid", false, modifiedAt = "invalid"),
            WebDavItem("z", "known", false, modifiedAt = "Wed, 21 Oct 2015 07:28:00 GMT"))
        for (order in listOf(BrowseSortOrder.MODIFIED_NEWEST, BrowseSortOrder.MODIFIED_OLDEST)) {
            assertEquals(listOf("known", "missing", "invalid"), sortWebDavItems(items, order).map { it.path })
            assertEquals(listOf("a", "missing"), sortLocalItems(listOf(files[1],
                LocalMediaItem("0.mkv", "missing", false)), order).map { it.uri })
        }
    }

    @Test fun tiesAreDeterministicAndSizeComparisonDoesNotOverflowOrLosePrecision() {
        val items = listOf(LocalMediaItem("same.mkv", "b", false, Long.MAX_VALUE),
            LocalMediaItem("same.mkv", "a", false, Long.MAX_VALUE),
            LocalMediaItem("near.mkv", "near", false, Long.MAX_VALUE - 1),
            LocalMediaItem("zero.mkv", "zero", false, 0),
            LocalMediaItem("unknown.mkv", "unknown", false, -1))
        assertEquals(listOf("zero", "near", "a", "b", "unknown"),
            sortLocalItems(items, BrowseSortOrder.SIZE_ASC).map { it.uri })
        assertEquals(listOf("a", "b", "near", "zero", "unknown"),
            sortLocalItems(items.reversed(), BrowseSortOrder.SIZE_DESC).map { it.uri })
        assertEquals(sortLocalItems(items, BrowseSortOrder.NAME_ASC), sortLocalItems(items.reversed(), BrowseSortOrder.NAME_ASC))
    }

    @Test fun sortUsesTheDisplayedFilenameAndRetainsEncryptedMetadata() {
        val encrypted = WebDavItem("z.m3u8sp", "package", false, 10,
            sourceFileName = "a.mkv", identifierPreview = "id", metadataLoading = true)
        val ordinary = WebDavItem("b.mkv", "ordinary", false, 20)
        val sorted = sortWebDavItems(listOf(encrypted, ordinary), BrowseSortOrder.NAME_ASC)
        assertEquals(listOf(ordinary, encrypted), sorted)
        assertSame(encrypted, sorted.last())
        assertTrue(sortLocalItems(emptyList(), BrowseSortOrder.NAME_ASC).isEmpty())
    }
}
