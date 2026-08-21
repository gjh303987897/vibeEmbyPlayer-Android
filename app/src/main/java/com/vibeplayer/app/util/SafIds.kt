package com.vibeplayer.app.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Helpers for working with Storage Access Framework document URIs of the form
 *
 *   content://<authority>/tree/<treeId>/document/<documentId>
 *
 * as produced by [DocumentsContract.buildDocumentUriUsingTree].
 */
object SafIds {

    /** The SAF tree URI (up to `/tree/<treeId>`) for a document URI, or null. */
    fun treeUriOf(documentUri: Uri): Uri? {
        val path = documentUri.path ?: return null
        val treeIndex = path.indexOf("/tree/")
        val documentIndex = path.indexOf("/document/")
        if (treeIndex < 0) return null
        val end = if (documentIndex > treeIndex) documentIndex else path.length
        return documentUri.buildUpon().path(path.substring(0, end)).build()
    }

    /** The document id portion after `/document/`, or the whole path if absent. */
    fun documentIdOf(documentUri: Uri): String? {
        val path = documentUri.path ?: return null
        val documentIndex = path.indexOf("/document/")
        return if (documentIndex >= 0) path.substring(documentIndex + "/document/".length) else null
    }

    /** True when this URI is a SAF tree/document URI managed by the provider. */
    fun isTreeUri(uri: Uri): Boolean =
        uri.scheme == "content" && (uri.path?.contains("/tree/") == true)
}
