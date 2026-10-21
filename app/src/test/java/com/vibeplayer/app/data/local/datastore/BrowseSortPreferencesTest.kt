package com.vibeplayer.app.data.local.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.BrowseSource
import org.junit.Assert.*
import org.junit.Test

class BrowseSortPreferencesTest {
    @Test fun missingOrUnknownPreferenceUsesNameAscending() {
        for (source in BrowseSource.entries) assertEquals(BrowseSortOrder.NAME_ASC, readBrowseSortOrder(emptyPreferences(), source))
        val stored = mutablePreferencesOf(stringPreferencesKey("webdav_browse_sort") to "future-mode")
        assertEquals(BrowseSortOrder.NAME_ASC, readBrowseSortOrder(stored, BrowseSource.WEBDAV))
    }

    @Test fun eachSourcesSixChoicesRoundTripWithoutOverwritingTheOtherOrUnrelatedSettings() {
        for (webdav in BrowseSortOrder.entries) for (local in BrowseSortOrder.entries) {
            val unrelated = booleanPreferencesKey("player_auto_play_next")
            val stored = mutablePreferencesOf(unrelated to false)
            stored.setBrowseSortOrder(BrowseSource.WEBDAV, webdav)
            stored.setBrowseSortOrder(BrowseSource.LOCAL, local)
            val snapshot = stored.toPreferences()
            assertEquals(webdav, readBrowseSortOrder(snapshot, BrowseSource.WEBDAV))
            assertEquals(local, readBrowseSortOrder(snapshot, BrowseSource.LOCAL))
            assertEquals(false, snapshot[unrelated])
        }
    }
}
