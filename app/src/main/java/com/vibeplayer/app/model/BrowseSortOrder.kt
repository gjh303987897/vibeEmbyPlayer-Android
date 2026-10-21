package com.vibeplayer.app.model

enum class BrowseSource { WEBDAV, LOCAL }

enum class BrowseSortOrder {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, MODIFIED_NEWEST, MODIFIED_OLDEST;

    companion object {
        fun fromStored(value: String?): BrowseSortOrder = entries.firstOrNull { it.name == value } ?: NAME_ASC
    }
}
