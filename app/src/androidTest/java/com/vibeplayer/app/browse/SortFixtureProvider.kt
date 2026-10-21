package com.vibeplayer.app.browse

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract.Document
import java.util.concurrent.atomic.AtomicInteger

/** Read-only SAF-shaped metadata to exercise the real ContentResolver/repository on device. */
class SortFixtureProvider : ContentProvider() {
    private val queries = AtomicInteger()
    override fun onCreate() = true

    // This provider runs in the test APK's separate process. Shared app dependencies
    // (including Kotlin stdlib) are intentionally absent there: use framework/JDK only.
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        queries.incrementAndGet()
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val cursor = MatrixCursor(columns)
        cursor.addRow(arrayOf<Any>("gamma.mp4", "gamma.mp4", "video/mp4", 100L, 3000L))
        cursor.addRow(arrayOf<Any>("FolderZ", "FolderZ", Document.MIME_TYPE_DIR, 0L, 1000L))
        cursor.addRow(arrayOf<Any>("beta.mp4", "beta.mp4", "video/mp4", 300L, 2000L))
        cursor.addRow(arrayOf<Any>("FolderA", "FolderA", Document.MIME_TYPE_DIR, 0L, 3000L))
        cursor.addRow(arrayOf<Any>("Alpha.mkv", "Alpha.mkv", "video/x-matroska", 600L, 1000L))
        cursor.addRow(arrayOf<Any>("readme.txt", "readme.txt", "text/plain", 1L, 4000L))
        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()
        result.putInt("queries", queries.get())
        return result
    }
    override fun getType(uri: Uri) = "vnd.android.document/directory"
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}
