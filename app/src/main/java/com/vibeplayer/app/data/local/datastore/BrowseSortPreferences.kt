package com.vibeplayer.app.data.local.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.BrowseSource

private fun sortKey(source: BrowseSource) = stringPreferencesKey(when (source) {
    BrowseSource.WEBDAV -> "webdav_browse_sort"
    BrowseSource.LOCAL -> "local_browse_sort"
})

internal fun readBrowseSortOrder(preferences: Preferences, source: BrowseSource): BrowseSortOrder =
    BrowseSortOrder.fromStored(preferences[sortKey(source)])

internal fun MutablePreferences.setBrowseSortOrder(source: BrowseSource, order: BrowseSortOrder) {
    this[sortKey(source)] = order.name
}
