package com.vibeplayer.app.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.vibeplayer.app.domain.subtitle.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class LocalSubtitleLoader @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun load(uri: Uri): TextSubtitleDocument = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw SubtitleImportException(SubtitleImportError.UNREADABLE)
        val resolver = context.contentResolver
        var label = "subtitle"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) label = cursor.getString(nameColumn)?.take(200).orEmpty().ifBlank { label }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn) && cursor.getLong(sizeColumn) > TextSubtitles.MAX_BYTES) {
                    throw SubtitleImportException(SubtitleImportError.TOO_LARGE)
                }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            TextSubtitles.readBounded(input) { coroutineContext.ensureActive() }
        } ?: throw SubtitleImportException(SubtitleImportError.UNREADABLE)
        TextSubtitles.parse(bytes, label, resolver.getType(uri)) { coroutineContext.ensureActive() }
    }
}
