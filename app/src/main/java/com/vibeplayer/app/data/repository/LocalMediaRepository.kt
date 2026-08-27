package com.vibeplayer.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vibeplayer.app.data.local.db.dao.LocalMediaRootDao
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.domain.local.LocalPlaybackService
import com.vibeplayer.app.model.LocalMediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Local media repository. Persisted local media roots store a SAF document URI
 * (the correct identity under Android scoped storage). Directory enumeration is
 * done one level at a time through the Storage Access Framework contract.
 */
@Singleton
class LocalMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootDao: LocalMediaRootDao
) {

    fun observeRoots(): Flow<List<LocalMediaRootEntity>> = rootDao.observeAll()

    suspend fun addRoot(uri: String, name: String) {
        val count = rootDao.getAll().size
        rootDao.upsert(
            LocalMediaRootEntity(
                id = uri,
                name = name,
                path = uri,
                sortOrder = count,
                available = hasPersistedPermission(uri)
            )
        )
    }

    /** True when the given tree URI holds a persisted read grant. */
    private fun hasPersistedPermission(uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        context.contentResolver.persistedUriPermissions.any { it.uri == uri }
    }.getOrDefault(false)

    /** Updates the persisted available flag for a root based on its grant. */
    suspend fun refreshRootAvailability() {
        rootDao.getAll().forEach { root ->
            val available = hasPersistedPermission(root.path)
            if (available != root.available) {
                rootDao.upsert(root.copy(available = available))
            }
        }
    }

    suspend fun removeRoot(id: String) = rootDao.deleteById(id)

    /**
     * Lists the immediate children of a SAF tree **or** sub-document URI.
     *
     * A subfolder that the user tapped is a document URI
     * (`…/tree/<treeId>/document/<docId>`); `getTreeDocumentId()` on it returns
     * the *root* id, which is why entering a subfolder used to re-list the root
     * folder again. The tree id and the requested document id are therefore read
     * from the path segments separately here.
     */
    suspend fun listChildren(parentUri: String): Result<List<LocalMediaItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(parentUri)
                val segments = uri.pathSegments
                require(segments.size >= 2 && segments[0] == "tree") {
                    "Not a local folder address"
                }
                val treeDocumentId = segments[1]
                val isDocumentUri = segments.size >= 4 && segments[2] == "document"
                val parentDocumentId = if (isDocumentUri) segments[3] else treeDocumentId
                val treeUri = DocumentsContract.buildTreeDocumentUri(
                    uri.authority ?: "",
                    treeDocumentId
                )
                val childrenUri =
                    DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
                enumerate(treeUri, childrenUri)
            }
        }

    private fun enumerate(treeUri: Uri, childrenUri: Uri): List<LocalMediaItem> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val results = mutableListOf<LocalMediaItem>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val documentId = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                val size = cursor.getLong(3)
                val modified = cursor.getLong(4)
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (!isDirectory && !LocalPlaybackService.isBrowsableMediaFile(name)) continue
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                results += if (isDirectory) {
                    LocalMediaItem(name = name, uri = documentUri.toString(), isDirectory = true)
                } else {
                    LocalMediaItem(
                        name = name,
                        uri = documentUri.toString(),
                        isDirectory = false,
                        size = size,
                        lastModified = modified
                    )
                }
            }
        }
        return results.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }
}
