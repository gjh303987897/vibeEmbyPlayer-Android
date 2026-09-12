package com.vibeplayer.app.player.hls

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SeekableHlsContainerSource {
    val length: Long
    suspend fun read(offset: Long, length: Long): Result<ByteArray>
}

/**
 * Reads the CBOR index with exactly two accesses: the 512-byte TAR header and
 * the index body the header advertises. Containers are multi-gigabyte, so the
 * previous fixed-prefix read made both listing and playback download megabytes
 * (and time out) before showing anything.
 */
suspend fun SeekableHlsContainerSource.readIndex(): EncryptedHlsTarIndex {
    val header = read(0, EncryptedHlsTarContainer.BLOCK_SIZE.toLong()).getOrThrow()
    val indexLength = EncryptedHlsTarContainer.indexLength(header)
    require(indexLength <= EncryptedHlsTarContainer.PREFIX_LIMIT.toLong()) { "M3U8SP index is too large" }
    val body = read(EncryptedHlsTarContainer.BLOCK_SIZE.toLong(), indexLength).getOrThrow()
    return EncryptedHlsTarContainer.readIndexPrefix(header + body, length)
}

class SafContainerSource(
    private val context: Context,
    treeUri: Uri,
    documentId: String,
    override val length: Long
) : SeekableHlsContainerSource {
    init { require(length > 0) }
    private val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override suspend fun read(offset: Long, length: Long): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(offset >= 0 && length > 0 && offset <= this@SafContainerSource.length - length)
            val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open M3U8SP container")
            descriptor.use { afd ->
                afd.createInputStream().use { input ->
                    var remaining = offset
                    while (remaining > 0) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0) throw IOException("Unable to seek M3U8SP container")
                        remaining -= skipped
                    }
                    val bytes = ByteArray(length.toInt())
                    var read = 0
                    while (read < bytes.size) {
                        val count = input.read(bytes, read, bytes.size - read)
                        if (count < 0) throw IOException("Truncated M3U8SP container")
                        read += count
                    }
                    bytes
                }
            }
        }
    }
}

class WebDavContainerSource(
    private val repository: WebDavRepository,
    private val server: ServerConfig,
    private val path: String,
    override val length: Long,
    private val etag: String,
    private val initialBytes: ByteArray
) : SeekableHlsContainerSource {
    init { require(length > 0 && initialBytes.isNotEmpty()) }
    override suspend fun read(offset: Long, length: Long): Result<ByteArray> {
        if (offset == 0L && length <= initialBytes.size) {
            return Result.success(initialBytes.copyOfRange(0, length.toInt()))
        }
        return repository.downloadRange(server, path, offset, length, this.length, etag)
    }
}

class IndexedTarHlsSource(
    private val source: SeekableHlsContainerSource,
    private val index: EncryptedHlsTarIndex
) : HlsByteSource {
    override suspend fun load(packageRelativePath: String): Result<ByteArray> = runCatching {
        val entry = index.entries[packageRelativePath]
            ?: throw IOException("M3U8SP entry is not registered: $packageRelativePath")
        val maximum = when {
            packageRelativePath == index.manifestPath -> MAX_MANIFEST_BYTES
            packageRelativePath.endsWith(".ts", ignoreCase = true) -> MAX_ENCRYPTED_SEGMENT_BYTES
            else -> MAX_RESOURCE_BYTES
        }
        require(entry.size <= maximum && entry.size <= Int.MAX_VALUE) {
            "M3U8SP entry exceeds the playback size limit"
        }
        val header = source.read(entry.headerOffset, 512).getOrThrow()
        EncryptedHlsTarContainer.verifyEntryHeader(header, entry)
        val bytes = source.read(entry.dataOffset, entry.size).getOrThrow()
        EncryptedHlsTarContainer.verifyEntry(entry, bytes)
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private const val MAX_ENCRYPTED_SEGMENT_BYTES = 512L * 1024 * 1024
        private const val MAX_RESOURCE_BYTES = 128L * 1024 * 1024
    }
}
