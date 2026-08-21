package com.vibeplayer.app.player.hls

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [HlsByteSource] reading a package from the Storage Access Framework.
 *
 * [treeUri] is the SAF tree the user granted; [baseDocumentId] is the document
 * id of the directory that contains the root manifest (all package-relative
 * paths are resolved beneath it, matching how TSSL stores relative paths).
 */
class SafHlsSource(
    private val context: Context,
    private val treeUri: Uri,
    private val baseDocumentId: String
) : HlsByteSource {

    override suspend fun load(packageRelativePath: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val documentId = if (packageRelativePath.isEmpty()) baseDocumentId
                else "$baseDocumentId/${packageRelativePath.split("/").joinToString("/")}"
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw java.io.IOException("Cannot open $packageRelativePath")
                input.use { it.readBytes() }
            }
        }
}

/**
 * [HlsByteSource] reading a package rooted at a filesystem [File] directory
 * (e.g. a packager output under application storage).
 */
class FileHlsSource(private val root: File) : HlsByteSource {

    override suspend fun load(packageRelativePath: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = File(root, packageRelativePath)
                if (!target.canonicalPath.startsWith(root.canonicalPath)) {
                    throw java.io.IOException("Path escapes package root")
                }
                if (!target.isFile) throw java.io.IOException("Missing $packageRelativePath")
                target.readBytes()
            }
        }
}

/**
 * [HlsByteSource] fetching encrypted package bytes from a remote WebDAV
 * location. [basePath] is the WebDAV directory containing the package
 * (relative to the service base URL). Authentication is resolved by the
 * [WebDavRepository] itself (securely stored password).
 */
class WebDavHlsSource(
    private val webDavRepository: WebDavRepository,
    private val server: ServerConfig,
    private val basePath: String
) : HlsByteSource {

    override suspend fun load(packageRelativePath: String): Result<ByteArray> {
        val remote = if (basePath.isEmpty()) packageRelativePath
        else "${basePath.trimEnd('/')}/$packageRelativePath"
        return webDavRepository.download(server, remote)
    }
}
