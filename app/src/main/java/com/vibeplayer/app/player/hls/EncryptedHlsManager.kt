package com.vibeplayer.app.player.hls

import android.content.Context
import android.net.Uri
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coordinates playback of an encrypted-HLS `.m3u8s` package, for both local
 * (SAF) and WebDAV sources, against a locally stored TSSL package.
 *
 * The flow mirrors the Qt EncryptedHlsPlaybackProxy contract:
 *  1. load the root manifest entity bytes,
 *  2. read its `#M3U8S-IDENTIFIER:<id>` metadata and find the local TSSL
 *     package with the same identifier,
 *  3. require the root-manifest digest to match `rootManifestSha256`,
 *  4. start a loopback [EncryptedHlsServer] backed by the right [HlsByteSource],
 *  5. return the playable `http://127.0.0.1:port/<rootManifestName>` URL.
 */
@Singleton
class EncryptedHlsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tsslStore: TsslStore,
    private val webDavRepository: WebDavRepository
) {

    /**
     * Starts playback of a local `.m3u8s` file inside an SAF tree.
     *
     * @param treeUri the SAF tree the user granted
     * @param manifestDocumentId the document id of the `.m3u8s` file itself
     */
    suspend fun prepareLocal(treeUri: Uri, manifestDocumentId: String): Result<EncryptedHlsPlayback> =
        runCatching {
            val manifestName = manifestDocumentId.substringAfterLast('/')
            val baseDocumentId = manifestDocumentId.substringBeforeLast('/', "")
            prepare(SafHlsSource(context, treeUri, baseDocumentId), manifestName)
        }

    /** Starts playback of a remote WebDAV `.m3u8s` located at [path]. */
    suspend fun prepareWebDav(server: ServerConfig, path: String): Result<EncryptedHlsPlayback> =
        runCatching {
            val basePath = path.substringBeforeLast('/', "")
            val manifestName = path.substringAfterLast('/')
            prepare(WebDavHlsSource(webDavRepository, server, basePath), manifestName)
        }

    private suspend fun prepare(
        source: HlsByteSource,
        rootName: String
    ): EncryptedHlsPlayback {
        val manifestBytes = source.load(rootName)
            .getOrElse { throw IllegalStateException("Unable to load manifest: ${it.message}") }
        val identifier = extractIdentifier(manifestBytes)
            ?: throw IllegalStateException("Manifest has no M3U8S identifier")

        val doc = tsslStore.list()
            .mapNotNull { pkg ->
                val raw = tsslStore.read(pkg.fileName) ?: return@mapNotNull null
                com.vibeplayer.app.domain.tssl.TsslDocument.parse(raw)
            }
            .firstOrNull { it.identifier == identifier }
            ?: throw IllegalStateException("No matching local TSSL package for this manifest")

        if (doc.rootManifestSha256.isNotEmpty() &&
            com.vibeplayer.app.domain.tssl.TsslCrypto.sha256Hex(manifestBytes) != doc.rootManifestSha256
        ) {
            throw IllegalStateException("Root manifest digest mismatch (tampered or stale)")
        }
        val manifestText = String(manifestBytes, Charsets.UTF_8)
        if (Regex("(?m)^#EXT-X-(SESSION-)?KEY:").containsMatchIn(manifestText)) {
            throw IllegalStateException("Root manifest carries EXT-X-KEY (keys belong only in TSSL)")
        }

        val server = EncryptedHlsServer(
            document = doc,
            source = source,
            rootManifestName = VIRTUAL_ROOT_NAME,
            rootManifestBytes = manifestBytes
        )
        server.start()
        return EncryptedHlsPlayback(
            server = server,
            rootManifestName = VIRTUAL_ROOT_NAME,
            resolvedSourceName = recoverSourceName(doc)
        )
    }

    /** Reads the `#M3U8S-IDENTIFIER:<id>` line near the manifest start. */
    private fun extractIdentifier(manifest: ByteArray): String? = try {
        val text = String(manifest, Charsets.UTF_8)
        Regex("(?m)^#M3U8S-IDENTIFIER:([A-Za-z0-9_-]+)$")
            .find(text)?.groupValues?.get(1)
    } catch (_: Exception) {
        null
    }

    /**
     * Recovers the authenticated original source basename from TSSL v3
     * (null for v2 or on failure). Display-only; never used to resolve a path.
     */
    private fun recoverSourceName(doc: com.vibeplayer.app.domain.tssl.TsslDocument): String? {
        val sn = doc.sourceName ?: return null
        return try {
            val plain = com.vibeplayer.app.domain.tssl.TsslCrypto.decrypt(
                sn.key,
                sn.encrypted,
                aad = doc.identifier.toByteArray(Charsets.UTF_8)
            )
            val name = String(plain, Charsets.UTF_8)
            if (name.contains('/') || name.contains('\\') || name.isBlank()) null else name
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Virtual name the root manifest is served under. Ends in `.m3u8` so
         * Media3's HLS detection recognises the stream; the actual stored file
         * (e.g. `index.m3u8s`) differs but is loaded and verified at setup time.
         */
        private const val VIRTUAL_ROOT_NAME = "index.m3u8"
    }
}
