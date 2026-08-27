package com.vibeplayer.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vibeplayer.app.data.local.db.dao.TransferTaskDao
import com.vibeplayer.app.data.local.db.entity.TransferStatus
import com.vibeplayer.app.data.local.db.entity.TransferTaskEntity
import com.vibeplayer.app.data.local.db.entity.TransferType
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.WebDavItem
import com.vibeplayer.app.service.TransferService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Single data entry point for the background transfer queue. Persists a
 * transfer queue in Room and executes downloads/uploads through the WebDAV
 * streaming client. Cancellation uses an in-memory set (seeded per service run)
 * for responsiveness; the persisted status still reflects the terminal state.
 *
 * Downloads stream into the SAF destination document URI; cancelled or failed
 * downloads delete the partial target, matching the Qt TransferManager cleanup
 * rules.
 */
@Singleton
class TransferRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TransferTaskDao,
    private val webDavRepository: WebDavRepository,
    private val mediaServerRepository: MediaServerRepository
) {

    /** Task ids the user has asked to cancel; checked by running workers. */
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()

    /** Task ids paused by the user; unlike cancellation, paused downloads keep their target. */
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()

    /** Used to dispatch (non-blocking) progress updates that touch Room DAOs. */
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Last progress write timestamp per task id, used to throttle DAO writes. */
    private val lastProgressWrite = ConcurrentHashMap<String, Long>()

    /** Throttled progress write: at most one Room update per task per interval. */
    private fun throttleProgress(taskId: String, bytes: Long) {
        val now = System.currentTimeMillis()
        val prev = lastProgressWrite[taskId] ?: 0L
        if (now - prev < PROGRESS_THROTTLE_MS && !cancelledIds.contains(taskId)) return
        lastProgressWrite[taskId] = now
        progressScope.launch { dao.updateProgress(taskId, bytes) }
    }

    fun observeTasks(): Flow<List<TransferTaskEntity>> = dao.observeAll()

    suspend fun enqueueDownload(
        server: ServerConfig,
        displayName: String,
        remotePath: String,
        localDocUri: String,
        totalBytes: Long
    ): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            TransferTaskEntity(
                id = id,
                type = TransferType.DOWNLOAD,
                serverId = server.id,
                serverName = server.name,
                remotePath = remotePath,
                displayName = displayName,
                localDest = localDocUri,
                status = TransferStatus.QUEUED,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                error = null,
                createdAtMs = System.currentTimeMillis()
            )
        )
        TransferService.start(context)
        return id
    }
    suspend fun enqueueUpload(
        server: ServerConfig,
        displayName: String,
        remotePath: String,
        localSourceUri: String,
        totalBytes: Long
    ): String {
        val id = UUID.randomUUID().toString()
        dao.upsert(
            TransferTaskEntity(
                id = id,
                type = TransferType.UPLOAD,
                serverId = server.id,
                serverName = server.name,
                remotePath = remotePath,
                displayName = displayName,
                localDest = localSourceUri,
                status = TransferStatus.QUEUED,
                totalBytes = totalBytes,
                transferredBytes = 0L,
                error = null,
                createdAtMs = System.currentTimeMillis()
            )
        )
        TransferService.start(context)
        return id
    }

    /**
     * Creates a destination document inside a user-chosen SAF directory tree and
     * enqueues a download of [item] from [server]. The returned id can be used
     * by the caller to start/resume the background queue.
     */
    suspend fun downloadToTree(
        server: ServerConfig,
        item: WebDavItem,
        destTreeUri: String
    ): String {
        val treeUri = Uri.parse(destTreeUri)
        val mime = if (item.isAudio) MIME_AUDIO else if (item.isVideo) MIME_VIDEO else MIME_OCTET
        var docUri = runCatching {
            DocumentsContract.createDocument(context.contentResolver, treeUri, mime, item.name)
        }.getOrNull()
        if (docUri == null) {
            throw IOException("Cannot create destination file")
        }
        return enqueueDownload(server, item.name, item.path, docUri.toString(), item.size)
    }

    suspend fun cancel(id: String) {
        pausedIds.remove(id)
        cancelledIds.add(id)
        dao.updateStatus(id, TransferStatus.CANCELED, null)
    }

    suspend fun retry(id: String) {
        pausedIds.remove(id)
        cancelledIds.remove(id)
        dao.updateStatus(id, TransferStatus.QUEUED, null)
        TransferService.start(context)
    }

    suspend fun pause(id: String) {
        cancelledIds.remove(id)
        pausedIds.add(id)
        dao.updateStatus(id, TransferStatus.PAUSED, null)
    }

    suspend fun resume(id: String) {
        pausedIds.remove(id)
        cancelledIds.remove(id)
        dao.updateStatus(id, TransferStatus.QUEUED, null)
        TransferService.start(context)
    }

    suspend fun remove(id: String) {
        pausedIds.remove(id)
        cancelledIds.remove(id)
        dao.deleteById(id)
    }

    suspend fun clearFinished() = dao.clearFinished()

    /** Executes one task after the service has atomically claimed it. */
    suspend fun runTask(task: TransferTaskEntity) = try {
        val updated = task.copy(status = TransferStatus.RUNNING, error = null, transferredBytes = 0L)
        when (task.type) {
            TransferType.DOWNLOAD -> runDownload(updated)
            TransferType.UPLOAD -> runUpload(updated)
        }
    } catch (e: Exception) {
        if (task.id in cancelledIds) {
            dao.updateStatus(task.id, TransferStatus.CANCELED, null)
        } else if (task.id in pausedIds) {
            dao.updateStatus(task.id, TransferStatus.PAUSED, null)
        } else {
            dao.updateStatus(task.id, TransferStatus.FAILED, e.message ?: "Transfer failed")
        }
    }

    private suspend fun runDownload(task: TransferTaskEntity) {
        val server = mediaServerRepository.getServer(task.serverId) ?: throw IOException("Server missing")
        val uri = Uri.parse(task.localDest)
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open destination")
        val onProgress: (Float) -> Unit = { f ->
            throttleProgress(task.id, (task.totalBytes * f).toLong())
        }
        val result = out.use { stream ->
            webDavRepository.streamDownload(
                server = server,
                path = task.remotePath,
                output = stream,
                totalBytes = task.totalBytes,
                onProgress = onProgress,
                isCancelled = { task.id in cancelledIds }
            )
        }
        if (result.isFailure) {
            if (task.id !in pausedIds) deleteDocument(uri)
            throw result.exceptionOrNull() ?: IOException("Download failed")
        }
        if (task.id in cancelledIds) {
            deleteDocument(uri)
            dao.updateStatus(task.id, TransferStatus.CANCELED, null)
        } else {
            dao.updateProgress(task.id, task.totalBytes)
            dao.updateStatus(task.id, TransferStatus.DONE, null)
        }
    }

    private suspend fun runUpload(task: TransferTaskEntity) {
        val server = mediaServerRepository.getServer(task.serverId) ?: throw IOException("Server missing")
        val uri = Uri.parse(task.localDest)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open source")
        val onProgress: (Float) -> Unit = { f ->
            throttleProgress(task.id, (task.totalBytes * f).toLong())
        }
        val result = input.use { stream ->
            webDavRepository.streamUpload(
                server = server,
                path = task.remotePath,
                input = stream,
                totalBytes = task.totalBytes,
                onProgress = onProgress,
                isCancelled = { task.id in cancelledIds }
            )
        }
        if (result.isFailure) {
            throw result.exceptionOrNull() ?: IOException("Upload failed")
        }
        if (task.id in cancelledIds) {
            dao.updateStatus(task.id, TransferStatus.CANCELED, null)
        } else {
            dao.updateProgress(task.id, task.totalBytes)
            dao.updateStatus(task.id, TransferStatus.DONE, null)
        }
    }

    private fun deleteDocument(uri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    companion object {
        private const val MIME_OCTET = "application/octet-stream"
        private const val MIME_VIDEO = "video/*"
        private const val MIME_AUDIO = "audio/*"

        /** Minimum interval (ms) between persisted progress writes per task. */
        private const val PROGRESS_THROTTLE_MS = 250L
    }
}
