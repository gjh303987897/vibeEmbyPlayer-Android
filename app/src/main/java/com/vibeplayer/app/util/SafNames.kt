package com.vibeplayer.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Small helpers for resolving SAF document metadata. */
object SafNames {

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
}
