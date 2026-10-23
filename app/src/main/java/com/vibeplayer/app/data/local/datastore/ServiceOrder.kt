package com.vibeplayer.app.data.local.datastore

import com.vibeplayer.app.model.ServerConfig

/** Replace only the selected slots, retaining hidden/new services and their latest configuration. */
internal fun reorderExistingServices(current: List<ServerConfig>, orderedIds: List<String>): List<ServerConfig> {
    val byId = current.associateBy { it.id }
    val selected = orderedIds.distinct().mapNotNull(byId::get)
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    val iterator = selected.iterator()
    return current.map { if (it.id in selectedIds) iterator.next() else it }
}
