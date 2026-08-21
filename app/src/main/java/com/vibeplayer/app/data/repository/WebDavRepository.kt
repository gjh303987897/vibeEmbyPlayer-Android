package com.vibeplayer.app.data.repository

import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.remote.webdav.WebDavClient
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.WebDavItem
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV repository. Wraps [WebDavClient] and resolves the securely stored
 * password for each request. Credentials never leave secure storage.
 */
@Singleton
class WebDavRepository @Inject constructor(
    private val client: WebDavClient,
    private val secureSessionStore: SecureSessionStore
) {

    fun saveCredentials(server: ServerConfig, password: String) {
        secureSessionStore.savePassword(server.id, password)
    }

    fun hasPassword(server: ServerConfig): Boolean = !secureSessionStore.password(server.id).isNullOrEmpty()

    /** Resolves the raw playback URL for a given server path. */
    fun playUrl(server: ServerConfig, path: String): String {
        val base = server.normalizedBaseUrl
        val normalized = if (path.isEmpty() || path == "/") "" else "/" + path.trimStart('/')
        return base + normalized
    }

    suspend fun list(server: ServerConfig, path: String): Result<List<WebDavItem>> {
        val password = secureSessionStore.password(server.id)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.list(server, password, path)
    }

    suspend fun createDirectory(server: ServerConfig, path: String): Result<Unit> {
        val password = secureSessionStore.password(server.id)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.createDirectory(server, password, path)
    }

    suspend fun download(server: ServerConfig, path: String): Result<ByteArray> {
        val password = secureSessionStore.password(server.id)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.download(server, password, path)
    }

    suspend fun upload(server: ServerConfig, path: String, bytes: ByteArray): Result<Unit> {
        val password = secureSessionStore.password(server.id)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.upload(server, password, path, bytes)
    }

    /** Password gate kept internal; relies on [requirePassword] below. */
    private fun requirePassword(server: ServerConfig): String? =
        secureSessionStore.password(server.id)

    suspend fun streamDownload(
        server: ServerConfig,
        path: String,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Unit> {
        val password = requirePassword(server)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.downloadTo(server, password, path, output, totalBytes, onProgress, isCancelled)
    }

    suspend fun streamUpload(
        server: ServerConfig,
        path: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Unit> {
        val password = requirePassword(server)
            ?: return Result.failure(IllegalStateException("WebDAV password not set"))
        return client.uploadFrom(server, password, path, input, totalBytes, onProgress, isCancelled)
    }
}
