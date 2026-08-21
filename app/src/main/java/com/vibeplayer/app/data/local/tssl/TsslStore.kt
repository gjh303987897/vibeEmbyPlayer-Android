package com.vibeplayer.app.data.local.tssl

import android.content.Context
import com.vibeplayer.app.model.TsslPackage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
class TsslStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val tsslDir: File
        get() = File(context.filesDir, TSSL_DIR)

    suspend fun list(): List<TsslPackage> = withContext(Dispatchers.IO) {
        val dir = tsslDir
        if (!dir.exists()) return@withContext emptyList()
        dir.listFiles { f -> f.isFile && f.name.endsWith(TSSL_EXT) }
            ?.map { f ->
                TsslPackage(
                    fileName = f.name,
                    sizeBytes = f.length(),
                    lastModifiedMillis = f.lastModified(),
                    identifierPreview = identifierPreview(f.readBytesOrNull())
                )
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()
    }

    suspend fun read(fileName: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(tsslDir, sanitize(fileName))
        if (!file.isFile) null else runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * Validates the TSSL document and atomically writes it to local storage.
     * Returns the stored filename, or null when the document is invalid.
     */
    suspend fun import(bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > MAX_TSSL_BYTES) return@withContext null
        if (!isValidTssl(bytes)) return@withContext null
        val dir = tsslDir
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        val name = digestFileName(bytes) + TSSL_EXT
        val target = File(dir, name)
        val written = runCatching {
            val tmp = File(dir, name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeBytes(bytes)
            }
            target.exists()
        }.getOrDefault(false)
        return@withContext if (written) name else null
    }

    suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(tsslDir, sanitize(fileName))
        file.exists() && file.delete()
    }

    suspend fun exportBytes(fileName: String): ByteArray? = read(fileName)

    private fun isValidTssl(bytes: ByteArray): Boolean = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        json.optString("format") == "TSSL" &&
            (json.optInt("version") == 2 || json.optInt("version") == 3) &&
            !json.optString("identifier").isNullOrEmpty()
    } catch (e: Exception) {
        false
    }

    /** Stable filename derived from package content only (never user input). */
    private fun digestFileName(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        return digest.take(64)
    }

    private fun identifierPreview(bytes: ByteArray?): String? {
        val json = try {
            bytes?.let { JSONObject(String(it, Charsets.UTF_8)) }
        } catch (e: Exception) {
            null
        } ?: return null
        val id = json.optString("identifier").takeIf { it.isNotEmpty() } ?: return null
        return if (id.length <= 28) id else id.take(16) + "…" + id.takeLast(12)
    }

    private fun File.readBytesOrNull(): ByteArray? = runCatching { readBytes() }.getOrNull()

    private fun sanitize(name: String): String = File(name).name

    companion object {
        private const val TSSL_DIR = "tssl"
        private const val TSSL_EXT = ".tssl"
        private const val MAX_TSSL_BYTES = 64 * 1024 * 1024
    }
}
