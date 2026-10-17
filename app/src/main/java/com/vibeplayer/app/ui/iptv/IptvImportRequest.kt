package com.vibeplayer.app.ui.iptv

import androidx.lifecycle.SavedStateHandle

internal data class IptvImportSelection(val serviceId: String, val uri: String)

/** One picker launch owns one result, including across Activity/process recreation. */
internal class IptvImportRequest(private val savedState: SavedStateHandle) {
    val pending: Boolean get() = savedState.get<String>(PENDING_SERVICE) != null

    fun begin(serviceId: String, importing: Boolean): Boolean {
        if (importing || pending) return false
        savedState[PENDING_SERVICE] = serviceId
        return true
    }

    /** Consume before doing any IO. Back's null result clears the request without an import. */
    fun complete(uri: String?): IptvImportSelection? {
        val serviceId = savedState.remove<String>(PENDING_SERVICE) ?: return null
        return uri?.let { IptvImportSelection(serviceId, it) }
    }

    fun cancel() { savedState.remove<String>(PENDING_SERVICE) }

    private companion object {
        const val PENDING_SERVICE = "iptv_import_pending_service"
    }
}
