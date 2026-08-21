package com.vibeplayer.app.domain.tssl

import android.content.Context
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.player.hls.FileHlsSource
import com.vibeplayer.app.player.hls.HlsByteSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds an encrypted-HLS (`index.m3u8s` + AES-256-GCM encrypted `.ts`
 * segments) package from an already HLS-ready source, plus the matching TSSL
 * v3 secret document stored in [TsslStore].
 *
 * The Qt packager transcodes arbitrary containers via FFmpeg; this Android
 * build does not bundle FFmpeg, so the source must already be an HLS media
 * playlist alongside its MPEG-TS segments. The cryptographic pipeline
 * (independent per-segment keys/IVs, in-memory authenticate-before-replace,
 * 4096-char identifier, encrypted source name, digest-named output) mirrors the
 * Qt behaviour.
 *
 * The root-manifest digest hashes the exact `index.m3u8s` entity bytes, so the
 * identifier and encrypted source-name metadata are embedded in the manifest
 * before that digest is computed.
 */
@Singleton
class EncryptedHlsPackager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tsslStore: TsslStore
) {

    sealed class PackageResult {
        data class Success(val packageDirName: String) : PackageResult()
        data class Error(val message: String) : PackageResult()
    }

    private val outputDir: File
        get() = File(context.filesDir, OUTPUT_DIR)

    /**
     * Packages an HLS asset reachable through [source]. [manifestFileName] is
     * the entry playlist name; the other playlist-relative `.ts` segments are
     * also read from [source]. [sourceDisplayName] is the original basename
     * encrypted into the TSSL metadata (display-only; never a path).
     * Reports cumulative progress 0..1 via [onProgress].
     */
    suspend fun packageFromHls(
        source: HlsByteSource,
        manifestFileName: String,
        sourceDisplayName: String,
        onProgress: (Float) -> Unit = {}
    ): PackageResult = withContext(Dispatchers.IO) {
        try {
            val manifestBytes = source.load(manifestFileName)
                .getOrElse { return@withContext PackageResult.Error("Missing playlist: $manifestFileName") }
            val entries = M3u8.parse(String(manifestBytes, Charsets.UTF_8))
            if (entries.isEmpty()) {
                return@withContext PackageResult.Error("Playlist has no media segments")
            }

            val identifier = TsslCrypto.newIdentifier()
            val nameKey = TsslCrypto.newKey()
            val nameIv = TsslCrypto.newIv()
            val sourceNameBlock = TsslCrypto.encrypt(
                key = nameKey,
                iv = nameIv,
                plain = sourceDisplayName.toByteArray(Charsets.UTF_8),
                aad = identifier.toByteArray(Charsets.UTF_8)
            )

            val staging = File(context.cacheDir, "pkg_stage_${System.nanoTime()}")
            staging.mkdirs()

            val segmentKeys = mutableListOf<TsslSegment>()
            for ((index, entry) in entries.withIndex()) {
                onProgress((index.toFloat() / entries.size) * 0.8f)
                val plain = source.load(entry.uri)
                    .getOrElse { return@withContext fail(staging, "Segment not found: ${entry.uri}") }
                val key = TsslCrypto.newKey()
                val iv = TsslCrypto.newIv()
                val encrypted = TsslCrypto.encrypt(key, iv, plain)
                if (!TsslCrypto.decrypt(key, encrypted).contentEquals(plain)) {
                    return@withContext fail(staging, "Encryption self-check failed for ${entry.uri}")
                }
                val segName = "segment_%06d.ts".format(index + 1)
                File(staging, segName).writeBytes(encrypted)
                segmentKeys.add(TsslSegment(segName, key))
            }

            val playlistBytes = buildPlaylist(entries, identifier, TsslCrypto.toBase64Url(sourceNameBlock))
                .toByteArray(Charsets.UTF_8)
            val rootDigest = TsslCrypto.sha256Hex(playlistBytes)

            val finalDir = File(outputDir, rootDigest)
            finalDir.mkdirs()
            File(finalDir, "index.m3u8s").writeBytes(playlistBytes)
            for (seg in segmentKeys) {
                File(staging, seg.path).copyTo(File(finalDir, seg.path), overwrite = true)
            }
            staging.deleteRecursively()

            val doc = TsslDocument(
                format = TsslDocument.FORMAT,
                version = 3,
                algorithm = TsslDocument.ALGORITHM,
                identifier = identifier,
                rootManifestSha256 = rootDigest,
                sourceName = TsslSourceName(encrypted = sourceNameBlock, key = nameKey),
                manifests = emptyList(),
                segments = segmentKeys,
                resources = emptyMap()
            )
            val stored = tsslStore.import(TsslDocument.toJsonBytes(doc))
            if (stored == null) {
                return@withContext PackageResult.Error("Failed to store TSSL package")
            }
            onProgress(1f)
            PackageResult.Success(rootDigest)
        } catch (e: Exception) {
            PackageResult.Error(e.message ?: "Packaging failed")
        }
    }

    /** Convenience for packaging from an app-private filesystem directory. */
    suspend fun packageFromDirectory(
        sourceDir: File,
        manifestFileName: String,
        sourceDisplayName: String,
        onProgress: (Float) -> Unit = {}
    ): PackageResult =
        packageFromHls(FileHlsSource(sourceDir), manifestFileName, sourceDisplayName, onProgress)

    private fun buildPlaylist(
        entries: List<HlsEntry>,
        identifier: String,
        sourceNameBase64: String
    ): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#M3U8S-IDENTIFIER:$identifier")
        appendLine("#M3U8S-SOURCE-NAME:$sourceNameBase64")
        for ((i, e) in entries.withIndex()) {
            val duration = e.duration?.let { "%.3f".format(it) } ?: "10.000"
            appendLine("#EXTINF:$duration,")
            appendLine("segment_%06d.ts".format(i + 1))
        }
        appendLine("#EXT-X-ENDLIST")
    }

    private fun fail(staging: File, message: String): PackageResult {
        runCatching { staging.deleteRecursively() }
        return PackageResult.Error(message)
    }

    private data class HlsEntry(val uri: String, val duration: Double?)

    private object M3u8 {
        fun parse(text: String): List<HlsEntry> {
            val result = mutableListOf<HlsEntry>()
            var pendingDuration: Double? = null
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF")) {
                    pendingDuration = Regex("""#EXTINF:\s*([0-9.]+)""")
                        .find(trimmed)?.groupValues?.get(1)?.toDoubleOrNull()
                    continue
                }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                // Only canonical relative segment URIs are accepted.
                if (trimmed.startsWith("/") || trimmed.contains('\\') || trimmed.contains("..")) continue
                result.add(HlsEntry(trimmed, pendingDuration))
                pendingDuration = null
            }
            return result
        }
    }

    companion object {
        private const val OUTPUT_DIR = "encryptedHls"
    }
}
