package com.vibeplayer.app.domain.tssl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** SAF owns public storage access. All methods are called on the backup worker. */
class TsslLocalBackup @Inject constructor(@ApplicationContext private val context: Context) {
    data class Entry(val name: String, val uri: Uri)

    fun list(tree: Uri, check: () -> Unit): List<Entry> {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val result = mutableListOf<Entry>()
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    check()
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(1) ?: continue
                    if (TsslBackupArchive.isBackupName(name)) result += Entry(name,
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                }
            } ?: throw IOException("Unable to list local backup folder")
        return result.sortedBy { it.name }
    }

    fun upload(tree: Uri, source: File, check: () -> Unit) {
        require(TsslBackupArchive.isBackupName(source.name))
        val existing = list(tree, check).firstOrNull { it.name == source.name }
        if (existing != null) {
            // Legacy names are deterministic. Leave a matching backup alone; never truncate old data.
            require(!TsslBackupArchive.isArchiveName(source.name)) { "Local backup already exists" }
            val old = context.contentResolver.openInputStream(existing.uri)?.use {
                TsslBackupArchive.readBounded(it, TsslBackupArchive.MAX_PACKAGE_BYTES, check)
            } ?: throw IOException("Unable to read existing local backup")
            require(TsslBackupArchive.sha256(old) == TsslBackupArchive.sha256(source, check)) { "Local backup already exists" }
            return
        }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val uri = DocumentsContract.createDocument(context.contentResolver, root,
            if (TsslBackupArchive.isArchiveName(source.name)) "application/zip" else "application/octet-stream", source.name)
            ?: throw IOException("Unable to create local backup")
        try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                require(it.moveToFirst() && it.getString(0) == source.name) { "Backup provider changed the filename" }
            } ?: throw IOException("Unable to verify local backup filename")
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> TsslBackupArchive.copyBounded(input, output, TsslBackupArchive.MAX_ARCHIVE_BYTES, check) }
            } ?: throw IOException("Unable to write local backup")
            check()
        } catch (error: Throwable) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw error
        }
    }

    fun download(uri: Uri, target: File, maximum: Long, check: () -> Unit) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use { output -> TsslBackupArchive.copyBounded(input, output, maximum, check) }
        } ?: throw IOException("Unable to read local backup")
    }

    /** File-picker imports may have misleading MIME types; cap legacy JSON before reading it all. */
    fun downloadSelected(uri: Uri, target: File, check: () -> Unit): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Unable to read selected backup")
        input.buffered().use { stream ->
            stream.mark(2)
            val archive = stream.read() == 0x50 && stream.read() == 0x4b
            stream.reset()
            target.outputStream().buffered().use { output ->
                TsslBackupArchive.copyBounded(stream, output,
                    if (archive) TsslBackupArchive.MAX_ARCHIVE_BYTES else TsslBackupArchive.MAX_PACKAGE_BYTES, check)
            }
            return archive
        }
    }
}
