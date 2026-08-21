package com.vibeplayer.app.domain.tssl

import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads local TSSL packages to a configured remote target, one package at a
 * time. Mirrors the Qt TsslBackupService: only `http`/`https` service URLs are
 * accepted, the remote path is appended below the base URL, and the original
 * local filename is preserved. Local packages are never removed after a
 * successful backup.
 */
@Singleton
class TsslBackupService @Inject constructor(
    private val webDavRepository: WebDavRepository
) {

    sealed class BackupResult {
        data class Success(val uploadedPath: String) : BackupResult()
        data class Error(val message: String) : BackupResult()
    }

    /** Maximum accepted package size; larger packages are rejected before upload. */
    private val maxPackageBytes = 256L * 1024 * 1024

    /**
     * Backs a single validated TSSL package up to [target] under
     * `tssl/<fileName>`. [bytes] must contain the exact package entity.
     */
    suspend fun backupToWebDav(
        target: ServerConfig,
        fileName: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {}
    ): BackupResult {
        if (target.serviceType != com.vibeplayer.app.model.ServiceType.WEBDAV) {
            return BackupResult.Error("Target is not a WebDAV service")
        }
        val base = target.normalizedBaseUrl.lowercase()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return BackupResult.Error("Remote path must start with http:// or https://")
        }
        if (bytes.isEmpty()) return BackupResult.Error("Package is empty")
        if (bytes.size > maxPackageBytes) {
            return BackupResult.Error("Package exceeds the 256 MiB size limit")
        }
        if (!webDavRepository.hasPassword(target)) {
            return BackupResult.Error("WebDAV credentials not set for ${target.name}")
        }
        val safeName = fileName.substringAfterLast('/').removeSurrounding("\"")
        val remotePath = "tssl/${safeName}"
        // PUT bodies are fully buffered in memory; report instantaneous progress.
        onProgress(0f)
        val result = webDavRepository.upload(target, remotePath, bytes)
        onProgress(1f)
        return result.fold(
            onSuccess = { BackupResult.Success("tssl/${safeName}") },
            onFailure = { BackupResult.Error(it.message ?: "Upload failed") }
        )
    }
}
