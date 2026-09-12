package com.vibeplayer.app.player.hls

import android.content.Context
import android.net.Uri
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument
import com.vibeplayer.app.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata exposed for an encrypted HLS item in a WebDAV listing. */
data class EncryptedHlsMetadata(
    /** Short identifier preview, or null when the manifest could not be read. */
    val identifierPreview: String?,
    /** Authenticated original source basename, when a matching TSSL exists. */
    val sourceFileName: String?
) {
    /** False when even the bounded manifest read produced nothing usable. */
    val available: Boolean get() = identifierPreview != null
}

/** Coordinates verified TSSL v2/v3 `.m3u8s` and TSSL v4 `.m3u8sp` playback. */
@Singleton
class EncryptedHlsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tsslStore: TsslStore,
    private val webDavRepository: WebDavRepository
) {
    suspend fun prepareLocal(
        treeUri: Uri,
        documentId: String,
        containerLength: Long = -1
    ): Result<EncryptedHlsPlayback> = withContext(Dispatchers.IO) {
        runCatching {
            val name = documentId.substringAfterLast('/')
            if (name.endsWith(".m3u8sp", ignoreCase = true)) {
                prepareContainer(SafContainerSource(context, treeUri, documentId, containerLength))
            } else {
                val baseDocumentId = documentId.substringBeforeLast('/', "")
                prepareDirectory(SafHlsSource(context, treeUri, baseDocumentId), name)
            }
        }
    }

    suspend fun prepareWebDav(
        server: ServerConfig,
        path: String,
        containerLength: Long = -1
    ): Result<EncryptedHlsPlayback> = withContext(Dispatchers.IO) {
        runCatching {
            if (path.endsWith(".m3u8sp", ignoreCase = true)) {
                // Only the TAR header is fetched up front; the index and root
                // manifest are read with exact ranges. Reading a fixed prefix
                // instead made startup wait for megabytes of container data and
                // often timed out before the player received a single frame.
                val header = webDavRepository.downloadInitialRange(
                    server, path, EncryptedHlsTarContainer.BLOCK_SIZE.toLong()
                ).getOrThrow()
                if (containerLength > 0) require(containerLength == header.totalLength)
                prepareContainer(
                    WebDavContainerSource(
                        webDavRepository, server, path, header.totalLength,
                        header.etag, header.bytes
                    )
                )
            } else {
                val basePath = path.substringBeforeLast('/', "")
                prepareDirectory(WebDavHlsSource(webDavRepository, server, basePath), path.substringAfterLast('/'))
            }
        }
    }

    /**
     * Reads the bounded root manifest needed to decorate a WebDAV list row.
     *
     * The 512-byte TAR header, the CBOR index and the manifest are fetched with
     * exact ranges (kilobytes for a multi-gigabyte `.m3u8sp`), and a `.m3u8s`
     * manifest is capped at [MANIFEST_METADATA_PREFIX_BYTES]. The identifier is
     * safe to show from the manifest alone, so rows still get a code when the
     * device has no matching TSSL yet; the original filename appears only after
     * the manifest and a local package authenticate each other.
     */
    suspend fun resolveWebDavMetadata(
        server: ServerConfig,
        path: String
    ): Result<EncryptedHlsMetadata> = withContext(Dispatchers.IO) {
        try {
            require(path.endsWith(".m3u8s", ignoreCase = true) ||
                path.endsWith(".m3u8sp", ignoreCase = true)) {
                "Not an encrypted HLS manifest"
            }
            val inspection = inspectWebDavManifest(server, path)
            val identifier = parseM3u8sManifestMetadata(inspection.manifest)?.identifier
                ?: parseM3u8sIdentifierPrefix(inspection.manifest)
            if (identifier == null) {
                return@withContext Result.success(EncryptedHlsMetadata(null, null))
            }
            val preview = TsslDocument.identifierPreview(identifier)
                ?: return@withContext Result.success(EncryptedHlsMetadata(null, null))
            // A truncated prefix cannot satisfy the manifest digest, so the
            // authenticated filename is only attempted for complete reads.
            val sourceName = if (inspection.complete) {
                localDocument(inspection.manifest, identifier)
                    ?.takeIf { matchesContainer(it, inspection) }
                    ?.let(::recoverSourceName)
            } else null
            Result.success(EncryptedHlsMetadata(preview, sourceName))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun matchesContainer(document: TsslDocument, inspection: WebDavManifestInspection): Boolean =
        if (inspection.containerLength == null) {
            document.version != 4
        } else {
            document.version == 4 &&
                document.containerFormat == TsslDocument.V4_CONTAINER_FORMAT &&
                document.containerLength == inspection.containerLength &&
                document.containerIndexSha256 == inspection.containerIndexSha256
        }

    private suspend fun prepareContainer(container: SeekableHlsContainerSource): EncryptedHlsPlayback {
        require(container.length > 0) { "M3U8SP container length is unavailable" }
        val index = container.readIndex()
        val source = IndexedTarHlsSource(container, index)
        val manifest = source.load(index.manifestPath).getOrThrow()
        require(manifest.size <= MAX_MANIFEST_BYTES) { "M3U8SP manifest is too large" }
        val doc = matchingDocument(manifest)
        require(validateHlsPlaylist(manifest, index.manifestPath, doc, root = true)) {
            "M3U8SP root playlist contains unsafe or unregistered URIs"
        }
        require(doc.version == 4) { "M3U8SP requires TSSL v4" }
        require(doc.containerFormat == TsslDocument.V4_CONTAINER_FORMAT)
        require(doc.containerLength == container.length) { "TSSL container length mismatch" }
        require(doc.containerIndexSha256 == index.sha256) { "TSSL container index digest mismatch" }
        return startPlayback(doc, source, manifest)
    }

    private suspend fun inspectWebDavManifest(
        server: ServerConfig,
        path: String
    ): WebDavManifestInspection {
        if (path.endsWith(".m3u8sp", ignoreCase = true)) {
            val header = webDavRepository.downloadInitialRange(
                server,
                path,
                EncryptedHlsTarContainer.BLOCK_SIZE.toLong()
            ).getOrThrow()
            val container = WebDavContainerSource(
                webDavRepository,
                server,
                path,
                header.totalLength,
                header.etag,
                header.bytes
            )
            val index = container.readIndex()
            val manifest = IndexedTarHlsSource(container, index)
                .load(index.manifestPath)
                .getOrThrow()
            require(manifest.size <= MAX_MANIFEST_BYTES) { "M3U8SP manifest is too large" }
            return WebDavManifestInspection(
                manifest = manifest,
                containerLength = index.containerLength,
                containerIndexSha256 = index.sha256
            )
        }

        val prefix = webDavRepository.downloadPrefix(server, path, MANIFEST_METADATA_PREFIX_BYTES).getOrThrow()
        require(prefix.bytes.size <= MAX_MANIFEST_BYTES) { "M3U8S manifest is too large" }
        // An over-limit manifest still yields its identifier header; only the
        // digest-authenticated filename needs the complete bytes.
        return WebDavManifestInspection(manifest = prefix.bytes, complete = prefix.complete)
    }

    private suspend fun prepareDirectory(source: HlsByteSource, rootName: String): EncryptedHlsPlayback {
        val manifest = source.load(rootName)
            .getOrElse { throw IllegalStateException("Unable to load manifest: ${it.message}") }
        require(manifest.size <= MAX_MANIFEST_BYTES) { "M3U8S manifest is too large" }
        val doc = matchingDocument(manifest)
        require(validateHlsPlaylist(manifest, rootName, doc, root = true)) {
            "M3U8S root playlist contains unsafe or unregistered URIs"
        }
        require(doc.version != 4) { "TSSL v4 must be used with an M3U8SP container" }
        return startPlayback(doc, source, manifest)
    }

    private suspend fun matchingDocument(manifest: ByteArray): TsslDocument {
        val metadata = parseM3u8sManifestMetadata(manifest)
            ?: throw IllegalStateException("Manifest has invalid M3U8S metadata")
        val identifier = metadata.identifier
        val digest = TsslCrypto.sha256Hex(manifest)
        val documents = tsslStore.list().mapNotNull { pkg ->
            tsslStore.read(pkg.fileName)?.let(TsslDocument::parse)
        }
        // The desktop resolves the package by root-manifest digest and only then
        // cross-checks the identifier. Selecting by identifier first made an
        // unrelated local package shadow this one, so playback failed with a
        // "digest mismatch" even though the matching TSSL was installed.
        val doc = documents.firstOrNull { it.rootManifestSha256 == digest }
            ?: throw IllegalStateException(
                if (documents.any { it.identifier == identifier }) {
                    "The local TSSL for this video does not match the remote package"
                } else {
                    "No matching local TSSL package for this manifest"
                }
            )
        require(doc.identifier == identifier && doc.identifier.length == TsslDocument.IDENTIFIER_LENGTH) {
            "Root manifest digest mismatch (tampered or stale)"
        }
        if (doc.version >= 3) {
            require(metadata.encryptedSourceName?.contentEquals(doc.sourceName?.encrypted) == true) {
                "Manifest and TSSL source filename metadata do not match"
            }
        } else {
            require(metadata.encryptedSourceName == null) {
                "TSSL v2 cannot authenticate source filename metadata"
            }
        }
        return doc
    }

    /**
     * Local package that owns [manifest], matched by root-manifest digest with
     * the identifier as a cross-check. Returns null (never throws) so a list row
     * can still show the identifier before the TSSL has been restored.
     */
    private suspend fun localDocument(manifest: ByteArray, identifier: String): TsslDocument? {
        val digest = TsslCrypto.sha256Hex(manifest)
        for (pkg in tsslStore.list()) {
            val document = tsslStore.read(pkg.fileName)?.let(TsslDocument::parse) ?: continue
            if (document.rootManifestSha256 == digest && document.identifier == identifier) return document
        }
        return null
    }

    private fun startPlayback(
        doc: TsslDocument,
        source: HlsByteSource,
        manifest: ByteArray
    ): EncryptedHlsPlayback {
        val text = String(manifest, Charsets.UTF_8)
        require(!Regex("(?m)^#EXT-X-(SESSION-)?KEY:").containsMatchIn(text)) {
            "Root manifest carries EXT-X-KEY (keys belong only in TSSL)"
        }
        val server = EncryptedHlsServer(doc, source, VIRTUAL_ROOT_NAME, manifest)
        server.start()
        return EncryptedHlsPlayback(server, VIRTUAL_ROOT_NAME, recoverSourceName(doc))
    }

    private fun recoverSourceName(doc: TsslDocument): String? {
        val source = doc.sourceName ?: return null
        return runCatching {
            // Qt source-name AAD includes this fixed domain-separation prefix.
            val qtAad = (SOURCE_NAME_AAD + doc.identifier).toByteArray(Charsets.UTF_8)
            val legacyAad = doc.identifier.toByteArray(Charsets.UTF_8)
            val plain = if (doc.version == 3) {
                runCatching { TsslCrypto.decrypt(source.key, source.encrypted, qtAad) }
                    .recoverCatching { TsslCrypto.decrypt(source.key, source.encrypted, legacyAad) }
                    .getOrThrow()
            } else {
                TsslCrypto.decrypt(source.key, source.encrypted, qtAad)
            }
            val name = plain.toString(Charsets.UTF_8)
            require(name.isNotBlank() && name != "." && name != ".." &&
                !name.contains('/') && !name.contains('\\') && name.none { it.code < 0x20 || it.code == 0x7f })
            name
        }.getOrNull()
    }

    private data class WebDavManifestInspection(
        val manifest: ByteArray,
        val complete: Boolean = true,
        val containerLength: Long? = null,
        val containerIndexSha256: String? = null
    )

    companion object {
        private const val VIRTUAL_ROOT_NAME = "index.m3u8"
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

        /** Enough for the identifier header of any realistic M3U8S manifest. */
        const val MANIFEST_METADATA_PREFIX_BYTES = 256L * 1024L
        private const val SOURCE_NAME_AAD = "vibeEmbyPlayerQT/M3U8S/source-name/v1\n"
    }
}
