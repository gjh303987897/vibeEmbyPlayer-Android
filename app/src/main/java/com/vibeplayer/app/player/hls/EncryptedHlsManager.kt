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
    val identifierPreview: String,
    /** Authenticated original source basename, when a matching TSSL exists. */
    val sourceFileName: String?
)

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
    ): Result<EncryptedHlsPlayback> = runCatching {
        val name = documentId.substringAfterLast('/')
        if (name.endsWith(".m3u8sp", ignoreCase = true)) {
            prepareContainer(SafContainerSource(context, treeUri, documentId, containerLength))
        } else {
            val baseDocumentId = documentId.substringBeforeLast('/', "")
            prepareDirectory(SafHlsSource(context, treeUri, baseDocumentId), name)
        }
    }

    suspend fun prepareWebDav(
        server: ServerConfig,
        path: String,
        containerLength: Long = -1
    ): Result<EncryptedHlsPlayback> = runCatching {
        if (path.endsWith(".m3u8sp", ignoreCase = true)) {
            val initial = webDavRepository.downloadInitialRange(
                server, path, EncryptedHlsTarContainer.PREFIX_LIMIT.toLong()
            ).getOrThrow()
            if (containerLength > 0) require(containerLength == initial.totalLength)
            prepareContainer(
                WebDavContainerSource(
                    webDavRepository, server, path, initial.totalLength,
                    initial.etag, initial.bytes
                )
            )
        } else {
            val basePath = path.substringBeforeLast('/', "")
            prepareDirectory(WebDavHlsSource(webDavRepository, server, basePath), path.substringAfterLast('/'))
        }
    }

    /**
     * Reads only the bounded root manifest needed to decorate a WebDAV list
     * row.  The identifier is safe to show as a short preview even when the
     * device does not yet have the matching TSSL package; the original source
     * filename is returned only after the manifest and local TSSL authenticate
     * each other.  `.m3u8sp` is inspected through its index/range path so the
     * complete container is never downloaded into memory.
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
            val metadata = parseM3u8sManifestMetadata(inspection.manifest)
                ?: throw IllegalStateException("Manifest has invalid M3U8S metadata")

            // A package may be browsed before its TSSL has been restored. Keep
            // the identifier visible in that case, but never guess/decrypt a
            // source filename without a fully matching local package.
            val document = try {
                matchingDocument(inspection.manifest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val sourceName = document
                ?.takeIf { doc ->
                    if (inspection.containerLength == null) {
                        doc.version != 4
                    } else {
                        doc.version == 4 &&
                            doc.containerFormat == TsslDocument.V4_CONTAINER_FORMAT &&
                            doc.containerLength == inspection.containerLength &&
                            doc.containerIndexSha256 == inspection.containerIndexSha256
                    }
                }
                ?.let(::recoverSourceName)

            Result.success(
                EncryptedHlsMetadata(
                    identifierPreview = TsslDocument.identifierPreview(metadata.identifier)
                        ?: throw IllegalStateException("Manifest has invalid M3U8S identifier"),
                    sourceFileName = sourceName
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun prepareContainer(container: SeekableHlsContainerSource): EncryptedHlsPlayback {
        require(container.length > 0) { "M3U8SP container length is unavailable" }
        val prefixLength = minOf(container.length, EncryptedHlsTarContainer.PREFIX_LIMIT.toLong())
        val prefix = container.read(0, prefixLength).getOrThrow()
        val index = EncryptedHlsTarContainer.readIndexPrefix(prefix, container.length)
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
            val initial = webDavRepository.downloadInitialRange(
                server,
                path,
                EncryptedHlsTarContainer.PREFIX_LIMIT.toLong()
            ).getOrThrow()
            val container = WebDavContainerSource(
                webDavRepository,
                server,
                path,
                initial.totalLength,
                initial.etag,
                initial.bytes
            )
            val prefixLength = minOf(container.length, EncryptedHlsTarContainer.PREFIX_LIMIT.toLong())
            val prefix = container.read(0, prefixLength).getOrThrow()
            val index = EncryptedHlsTarContainer.readIndexPrefix(prefix, container.length)
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

        val manifest = webDavRepository.download(server, path).getOrThrow()
        require(manifest.size <= MAX_MANIFEST_BYTES) { "M3U8S manifest is too large" }
        return WebDavManifestInspection(manifest)
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
        val doc = tsslStore.list().mapNotNull { pkg ->
            tsslStore.read(pkg.fileName)?.let(TsslDocument::parse)
        }.firstOrNull { it.identifier == identifier }
            ?: throw IllegalStateException("No matching local TSSL package for this manifest")
        require(doc.identifier.length == TsslDocument.IDENTIFIER_LENGTH)
        require(TsslCrypto.sha256Hex(manifest) == doc.rootManifestSha256) {
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
        val containerLength: Long? = null,
        val containerIndexSha256: String? = null
    )

    companion object {
        private const val VIRTUAL_ROOT_NAME = "index.m3u8"
        private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        private const val SOURCE_NAME_AAD = "vibeEmbyPlayerQT/M3U8S/source-name/v1\n"
    }
}
