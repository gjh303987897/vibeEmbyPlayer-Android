package com.vibeplayer.app.domain.tssl

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.remote.backupResponse
import com.vibeplayer.app.data.remote.backupRequestBody
import com.vibeplayer.app.data.remote.backupToFile
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

sealed interface TsslBackupTarget {
    data class Local(val treeUri: String) : TsslBackupTarget
    data class WebDav(val server: ServerConfig, val path: String) : TsslBackupTarget
    data class S3(val endpoint: String, val bucket: String, val region: String, val prefix: String,
        val accessKey: String, val secretKey: String, val trustSelfSignedCertificate: Boolean = false) : TsslBackupTarget
}

data class TsslRestoreSummary(val restored: Int, val alreadyExists: Int, val failed: Int, val firstError: String? = null)

/** ZIP snapshots by default; mixed ZIP/legacy restores share the same validated import workflow. */
@Singleton
class TsslBackupService @Inject constructor(
    private val tsslStore: TsslStore,
    private val webDavRepository: WebDavRepository,
    private val clientFactory: OkHttpClientFactory,
    private val local: TsslLocalBackup,
    private val importer: TsslImportWorkflow,
    @ApplicationContext private val context: Context
) {
    sealed class BackupResult {
        data class Success(val uploaded: Int) : BackupResult()
        data class Error(val message: String) : BackupResult()
    }
    sealed class RestoreResult {
        data class Success(val summary: TsslRestoreSummary) : RestoreResult()
        data class Error(val message: String) : RestoreResult()
    }

    suspend fun backup(
        target: TsslBackupTarget,
        fileNames: List<String>,
        format: TsslBackupFormat = TsslBackupFormat.ZIP,
        onStage: (TsslBackupStage) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BackupResult = withContext(Dispatchers.IO) {
        validateTarget(target)?.let { return@withContext BackupResult.Error(it) }
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "tssl-backup-").toFile()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive() }
        try {
            require(fileNames.size in 1..TsslBackupArchive.MAX_PACKAGES) { "No valid TSSL packages or too many packages" }
            onStage(TsslBackupStage.SCANNING)
            val names = fileNames.map { safePackageName(it) ?: throw IOException("Invalid TSSL package filename") }
            require(names.distinctBy { it.lowercase() }.size == names.size) { "Duplicate TSSL package filenames" }
            var stagedBytes = 0L
            suspend fun stage(name: String): File {
                check()
                val bytes = tsslStore.exportBytes(name) ?: throw IOException("Unable to read TSSL package")
                stagedBytes += bytes.size
                if (format == TsslBackupFormat.ZIP) require(stagedBytes <= TsslBackupArchive.MAX_EXPANDED_BYTES) { "Expanded backup exceeds 2 GiB" }
                return File(work, name.lowercase()).apply { writeBytes(bytes) }
            }
            val archive = if (format == TsslBackupFormat.ZIP) {
                val files = names.map { stage(it) }
                onStage(TsslBackupStage.COMPRESSING)
                File(work, TsslBackupArchive.newFileName()).also { TsslBackupArchive.create(files, it, check, onProgress) }
            } else null
            onStage(TsslBackupStage.UPLOADING)
            val total = if (archive != null) 1 else names.size
            onProgress(0, total)
            repeat(total) { index ->
                check()
                val file = archive ?: stage(names[index])
                when (target) {
                    is TsslBackupTarget.Local -> local.upload(Uri.parse(target.treeUri), file, check)
                    is TsslBackupTarget.WebDav -> webDavRepository.uploadBackup(target.server, joinPath(target.path, file.name), file).getOrThrow()
                    is TsslBackupTarget.S3 -> uploadS3(target, file)
                }
                if (archive == null) file.delete()
                onProgress(index + 1, total)
            }
            BackupResult.Success(fileNames.size)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { BackupResult.Error("Unable to create or upload TSSL backup") }
        finally { work.deleteRecursively() }
    }

    suspend fun restore(target: TsslBackupTarget, onStage: (TsslBackupStage) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }): RestoreResult = withContext(Dispatchers.IO) {
        validateTarget(target)?.let { return@withContext RestoreResult.Error(it) }
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "tssl-restore-").toFile()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive() }
        try {
            onStage(TsslBackupStage.LISTING)
            val localFiles = if (target is TsslBackupTarget.Local) local.list(Uri.parse(target.treeUri), check) else emptyList()
            val remote = when (target) {
                is TsslBackupTarget.Local -> localFiles.map { it.name }
                is TsslBackupTarget.WebDav -> webDavRepository.listBackups(target.server, cleanPath(target.path)).getOrThrow()
                    .filter { !it.isDirectory && TsslBackupArchive.isBackupName(it.path.substringAfterLast('/')) }
                    .map { it.path }.distinct().sorted()
                is TsslBackupTarget.S3 -> listS3(target).getOrThrow()
            }
            var summary = TsslRestoreSummary(0, 0, 0)
            onProgress(0, remote.size)
            remote.forEachIndexed { index, name ->
                check()
                val fileName = name.substringAfterLast('/')
                require(TsslBackupArchive.isBackupName(fileName)) { "Invalid backup filename" }
                val file = File(work, fileName)
                try {
                    onStage(TsslBackupStage.DOWNLOADING)
                    val maximum = if (TsslBackupArchive.isArchiveName(fileName)) TsslBackupArchive.MAX_ARCHIVE_BYTES else MAX_PACKAGE_BYTES
                    when (target) {
                        is TsslBackupTarget.Local -> local.download(localFiles.first { it.name == name }.uri, file, maximum, check)
                        is TsslBackupTarget.WebDav -> webDavRepository.downloadBackup(target.server, name, file, maximum).getOrThrow()
                        is TsslBackupTarget.S3 -> downloadS3(target, name, file, maximum)
                    }
                    summary = add(summary, importFile(file, File(work, "extracted-$index"), check, onStage))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { summary = summary.copy(failed = summary.failed + 1, firstError = summary.firstError ?: "Invalid or unreadable TSSL backup") }
                finally { file.delete() }
                onProgress(index + 1, remote.size)
            }
            RestoreResult.Success(summary)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RestoreResult.Error("Unable to list or restore TSSL backups") }
        finally { work.deleteRecursively() }
    }

    /** SAF import streams into private cache before dispatching ZIP or legacy validation. */
    suspend fun importUri(uri: Uri, onStage: (TsslBackupStage) -> Unit = {}): RestoreResult = withContext(Dispatchers.IO) {
        val work = Files.createTempDirectory(context.cacheDir.toPath(), "tssl-import-").toFile()
        val jobContext = coroutineContext
        val check = { jobContext.ensureActive() }
        try {
            val file = File(work, "selected")
            onStage(TsslBackupStage.DOWNLOADING)
            // Sniff content, not an unreliable provider MIME type or changed display name.
            val archive = local.downloadSelected(uri, file, check)
            val input = if (archive) File(work, "selected.zip").also { Files.move(file.toPath(), it.toPath()) } else file
            RestoreResult.Success(importFile(input, File(work, "extracted"), check, onStage))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { RestoreResult.Error("Invalid or unreadable TSSL backup") }
        finally { work.deleteRecursively() }
    }

    private suspend fun importFile(file: File, extracted: File, check: () -> Unit, onStage: (TsslBackupStage) -> Unit): TsslRestoreSummary {
        return importer.run(listOf(file), extracted, check, onStage)
    }

    private fun add(a: TsslRestoreSummary, b: TsslRestoreSummary) = TsslRestoreSummary(
        a.restored + b.restored, a.alreadyExists + b.alreadyExists, a.failed + b.failed, a.firstError ?: b.firstError)

    /** Compatibility API for callers explicitly transferring one legacy document. */
    suspend fun backupToWebDav(target: ServerConfig, fileName: String, bytes: ByteArray, onProgress: (Float) -> Unit = {}): BackupResult =
        withContext(Dispatchers.IO) {
            if (safePackageName(fileName) == null || TsslDocument.parse(bytes) == null || bytes.size > MAX_PACKAGE_BYTES)
                return@withContext BackupResult.Error("Invalid TSSL package")
            val work = Files.createTempDirectory(context.cacheDir.toPath(), "tssl-upload-").toFile()
            try {
                val file = File(work, fileName).apply { writeBytes(bytes) }
                onProgress(0f)
                webDavRepository.uploadBackup(target, joinPath(DEFAULT_REMOTE_PATH, fileName), file).getOrThrow()
                onProgress(1f)
                BackupResult.Success(1)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { BackupResult.Error("Unable to upload TSSL package") }
            finally { work.deleteRecursively() }
        }

    private suspend fun uploadS3(target: TsslBackupTarget.S3, file: File) {
        val jobContext = coroutineContext
        val payloadHash = TsslBackupArchive.sha256(file) { jobContext.ensureActive() }
        val type = if (TsslBackupArchive.isArchiveName(file.name)) "application/zip".toMediaType() else JSON_MEDIA_TYPE
        val url = objectUrl(target, objectKey(target.prefix, file.name))
        val request = signedRequest(target, "PUT", url, payloadHash).put(file.backupRequestBody(type, jobContext)).build()
        clientFor(target).newCall(request).backupResponse { if (!it.isSuccessful) throw IOException("S3 PUT HTTP ${it.code}") }
    }

    private suspend fun listS3(target: TsslBackupTarget.S3): Result<List<String>> {
        val jobContext = coroutineContext
        return runCatching {
            val files = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var continuation: String? = null
            var pages = 0
            do {
                jobContext.ensureActive()
                require(++pages <= 10_000) { "Too many S3 listing pages" }
                val builder = objectUrl(target, null).newBuilder().addQueryParameter("list-type", "2")
                val prefix = cleanPrefix(target.prefix)
                if (prefix.isNotEmpty()) builder.addQueryParameter("prefix", "$prefix/")
                continuation?.let { builder.addQueryParameter("continuation-token", it) }
                val request = signedRequest(target, "GET", builder.build(), EMPTY_SHA256).build()
                val page = clientFor(target).newCall(request).backupResponse { response ->
                    if (!response.isSuccessful) throw IOException("S3 LIST HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty S3 listing")
                    require(body.contentLength() <= 4L * 1024 * 1024) { "S3 listing too large" }
                    parseS3List(body.byteStream().use { TsslBackupArchive.readBounded(it, 4L * 1024 * 1024) { jobContext.ensureActive() } })
                }
                files += page.keys.filter { key ->
                    TsslBackupArchive.isBackupName(key.substringAfterLast('/')) &&
                        (prefix.isEmpty() || key.startsWith("$prefix/"))
                }
                continuation = if (page.truncated) page.nextToken.also {
                    require(it.isNotEmpty() && seen.add(it)) { "Invalid S3 continuation token" }
                } else null
            } while (continuation != null)
            files.distinct().sorted()
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun downloadS3(target: TsslBackupTarget.S3, key: String, file: File, maximum: Long) {
        val jobContext = coroutineContext
        val request = signedRequest(target, "GET", objectUrl(target, key), EMPTY_SHA256).build()
        clientFor(target).newCall(request).backupResponse { it.backupToFile(file, maximum, jobContext) }
    }

    private fun validateTarget(target: TsslBackupTarget): String? = when (target) {
        is TsslBackupTarget.Local -> if (Uri.parse(target.treeUri).scheme != "content") "Select a local backup folder" else null
        is TsslBackupTarget.WebDav -> {
            if (target.server.serviceType != ServiceType.WEBDAV) "Target is not a WebDAV service"
            else if (!target.server.normalizedBaseUrl.startsWith("http://", true) &&
                !target.server.normalizedBaseUrl.startsWith("https://", true)
            ) "The WebDAV backup endpoint is invalid"
            else if (cleanPath(target.path).isEmpty()) "Enter a WebDAV backup folder"
            else null
        }
        is TsslBackupTarget.S3 -> {
            val endpoint = target.endpoint.trim().toHttpUrlOrNull()
            when {
                endpoint == null -> "Enter a valid S3 endpoint"
                endpoint.scheme != "https" && !isLocalhost(endpoint.host) ->
                    "S3 requires HTTPS (except localhost development endpoints)"
                !S3_BUCKET.matches(target.bucket.trim()) -> "Enter a valid S3 bucket name"
                target.region.trim().isEmpty() -> "Enter an S3 region"
                target.accessKey.trim().isEmpty() || target.secretKey.isEmpty() ->
                    "Complete the S3 access key and secret key"
                else -> null
            }
        }
    }

    private fun clientFor(target: TsslBackupTarget.S3): OkHttpClient =
        clientFactory.client(target.trustSelfSignedCertificate).newBuilder()
            .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).build()

    private fun objectUrl(target: TsslBackupTarget.S3, key: String?): HttpUrl {
        val builder = target.endpoint.trim().toHttpUrlOrNull()
            ?: error("Invalid S3 endpoint")
        val result = builder.newBuilder().addPathSegment(target.bucket.trim())
        key?.split('/')?.filter { it.isNotEmpty() }?.forEach(result::addPathSegment)
        return result.build()
    }

    private fun signedRequest(
        target: TsslBackupTarget.S3,
        method: String,
        url: HttpUrl,
        payloadHash: String
    ): Request.Builder {
        val now = Instant.now()
        val date = UTC_DATE.format(now)
        val timestamp = UTC_TIMESTAMP.format(now)
        val host = url.host + if (url.port != if (url.scheme == "https") 443 else 80) ":${url.port}" else ""
        val canonicalHeaders = "host:$host\n" +
            "x-amz-content-sha256:$payloadHash\n" +
            "x-amz-date:$timestamp\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonicalQuery = url.queryParameterNames
            .flatMap { name -> url.queryParameterValues(name).map { value -> canonicalEncode(name) to canonicalEncode(value.orEmpty()) } }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .joinToString("&") { (name, value) -> "$name=$value" }
        val canonicalRequest = listOf(
            method,
            url.encodedPath,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")
        val scope = "$date/${target.region.trim()}/s3/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$timestamp\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
        val dateKey = hmac("AWS4${target.secretKey}".toByteArray(), date)
        val regionKey = hmac(dateKey, target.region.trim())
        val serviceKey = hmac(regionKey, "s3")
        val signingKey = hmac(serviceKey, "aws4_request")
        val signature = hmac(signingKey, stringToSign).toHex()
        return Request.Builder()
            .url(url)
            .header("Host", host)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", timestamp)
            .header(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=${target.accessKey.trim()}/$scope, " +
                    "SignedHeaders=$signedHeaders, Signature=$signature"
            )
    }

    private fun parseS3List(bytes: ByteArray): S3ListPage {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(bytes.inputStream(), "UTF-8")
        }
        val keys = mutableListOf<String>()
        var truncated = false
        var nextToken = ""
        var rootSeen = false
        var inContents = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (!rootSeen) {
                        require(parser.name.orEmpty().substringAfterLast(':') == "ListBucketResult") { "Invalid S3 listing root" }
                        rootSeen = true
                    }
                    when (parser.name.orEmpty().substringAfterLast(':')) {
                        "Contents" -> if (parser.depth == 2) inContents = true
                        "Key" -> if (inContents && parser.depth == 3) keys += parser.nextText()
                        "IsTruncated" -> if (parser.depth == 2) truncated = parser.nextText().trim().equals("true", true)
                        "NextContinuationToken" -> if (parser.depth == 2) nextToken = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.depth == 2 && parser.name == "Contents") inContents = false
            }
            event = parser.next()
        }
        require(rootSeen) { "Empty S3 listing" }
        return S3ListPage(keys, truncated, nextToken)
    }

    private data class S3ListPage(val keys: List<String>, val truncated: Boolean, val nextToken: String)

    private fun safePackageName(value: String): String? {
        val name = value
        return name.takeIf { it.matches(Regex("^[0-9a-fA-F]{64}\\.tssl$")) }
    }

    private fun objectKey(prefix: String, fileName: String): String {
        val clean = cleanPrefix(prefix)
        return if (clean.isEmpty()) fileName else "$clean/$fileName"
    }

    private fun joinPath(folder: String, fileName: String): String {
        val clean = cleanPath(folder)
        return if (clean.isEmpty()) fileName else "$clean/$fileName"
    }

    private fun cleanPath(path: String): String = path.trim().trim('/').replace(Regex("/+"), "/")

    private fun cleanPrefix(prefix: String): String = cleanPath(prefix)

    private fun isLocalhost(host: String): Boolean =
        host.equals("localhost", true) || host == "127.0.0.1" || host == "::1"

    private fun canonicalEncode(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val c = byte.toInt() and 0xff
            if (c in 0x41..0x5a || c in 0x61..0x7a || c in 0x30..0x39 || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                append(c.toChar())
            } else {
                append('%')
                append("0123456789ABCDEF"[c ushr 4])
                append("0123456789ABCDEF"[c and 0x0f])
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, algorithm))
        doFinal(value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val DEFAULT_REMOTE_PATH = "vibePlayerQT/tssl"
        private const val MAX_PACKAGE_BYTES = TsslBackupArchive.MAX_PACKAGE_BYTES
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_SHA256 = sha256HexStatic(byteArrayOf())
        private val S3_BUCKET = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        private val UTC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        private val UTC_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun sha256HexStatic(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
