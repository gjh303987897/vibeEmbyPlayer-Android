package com.vibeplayer.app.data.local.tssl

import android.content.Context
import com.vibeplayer.app.domain.tssl.TsslDocument
import com.vibeplayer.app.domain.tssl.TsslBackupArchive
import java.nio.file.Files
import com.vibeplayer.app.model.TsslPackage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages local TSSL packages in application-private storage
 * (`filesDir/tssl`). Packages are secrets (they contain media keys) and are
 * never uploaded automatically; restore/export are explicit user operations.
 *
 * Follows the Qt reference behaviour: the restored filename is derived from
 * package content rather than untrusted input, invalid/damaged files remain
 * visible for diagnosis but are not treated as exportable packages.
 */
@Singleton
class TsslStore internal constructor(
    private val tsslDir: File
) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, TSSL_DIR))
    private val writes = Mutex()

    sealed class RestoreResult {
        data class Stored(val fileName: String) : RestoreResult()
        data class AlreadyExists(val fileName: String) : RestoreResult()
        data class Invalid(val reason: String = "Invalid TSSL package") : RestoreResult()
    }

    suspend fun list(): List<TsslPackage> = withContext(Dispatchers.IO) {
        val dir = tsslDir
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(TSSL_EXT) }
            ?.map { f ->
                val bytes = f.readBytesOrNull()
                val document = bytes?.let(TsslDocument::parse)
                val validName = document != null &&
                    f.name.equals("${document.rootManifestSha256.lowercase()}$TSSL_EXT", ignoreCase = true)
                TsslPackage(
                    fileName = f.name,
                    sizeBytes = f.length(),
                    lastModifiedMillis = f.lastModified(),
                    identifierPreview = if (validName) {
                        document?.let { TsslDocument.identifierPreview(it.identifier) }
                    } else {
                        null
                    },
                    isValid = validName
                )
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(tsslDir, sanitize(fileName))
        if (!file.isFile) null else file.readBytesOrNull()
    }

    /**
     * Validates the TSSL document and atomically writes it to local storage.
     * Returns the stored filename, or null when the document is invalid.
     */
    suspend fun import(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        when (val result = restore(bytes)) {
            is RestoreResult.Stored -> result.fileName
            is RestoreResult.AlreadyExists,
            is RestoreResult.Invalid -> null
        }
    }

    /**
     * Validates and restores a package without replacing an existing digest.
     * The result distinguishes duplicate backups from malformed files so the
     * remote restore summary can match the desktop implementation.
     */
    suspend fun restore(bytes: ByteArray): RestoreResult = withContext(Dispatchers.IO) {
        writes.withLock {
            if (bytes.isEmpty() || bytes.size > MAX_TSSL_BYTES) {
                return@withLock RestoreResult.Invalid("TSSL package is empty or too large")
            }
            val document = TsslDocument.parse(bytes)
                ?: return@withLock RestoreResult.Invalid()
            val dir = tsslDir
            if (!dir.exists() && !dir.mkdirs()) {
                return@withLock RestoreResult.Invalid("Unable to create local TSSL storage")
            }
            val name = document.rootManifestSha256.lowercase() + TSSL_EXT
            val target = File(dir, name)
            if (target.exists()) return@withLock RestoreResult.AlreadyExists(name)
            val written = runCatching {
                val tmp = File.createTempFile(".tssl-import-", ".tmp", dir)
                try {
                    tmp.writeBytes(bytes)
                    Files.move(tmp.toPath(), target.toPath())
                    target.isFile
                } finally { tmp.delete() }
            }.getOrDefault(false)
            if (written) RestoreResult.Stored(name)
            else if (target.exists()) RestoreResult.AlreadyExists(name)
            else RestoreResult.Invalid("Unable to save local TSSL package")
        }
    }

    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(tsslDir, sanitize(fileName))
        file.exists() && file.delete()
    }

    suspend fun exportBytes(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(tsslDir, sanitize(fileName))
        val bytes = if (file.isFile) file.readBytesOrNull() else null
        val document = bytes?.let(TsslDocument::parse) ?: return@withContext null
        if (!file.name.equals("${document.rootManifestSha256.lowercase()}$TSSL_EXT", ignoreCase = true)) {
            return@withContext null
        }
        // Archive manifests hash the original bytes. Do not reserialize unknown fields/whitespace.
        bytes
    }

    private fun File.readBytesOrNull(): ByteArray? = runCatching { TsslBackupArchive.readPackage(this) }.getOrNull()

    private fun sanitize(name: String): String = File(name).name

    companion object {
        private const val TSSL_DIR = "tssl"
        private const val TSSL_EXT = ".tssl"
        private const val MAX_TSSL_BYTES = 64 * 1024 * 1024
    }
}
