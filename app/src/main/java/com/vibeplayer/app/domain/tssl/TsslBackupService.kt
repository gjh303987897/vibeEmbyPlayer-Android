package com.vibeplayer.app.domain.tssl

import android.util.Xml
import com.vibeplayer.app.data.local.tssl.TsslStore
import com.vibeplayer.app.data.repository.WebDavRepository
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser

sealed interface TsslBackupTarget {
    data class WebDav(val server: ServerConfig, val path: String) : TsslBackupTarget

    data class S3(
        val endpoint: String,
        val bucket: String,
        val region: String,
        val prefix: String,
        val accessKey: String,
        val secretKey: String,
        val trustSelfSignedCertificate: Boolean = false
    ) : TsslBackupTarget
}

data class TsslRestoreSummary(
    val restored: Int,
    val alreadyExists: Int,
    val failed: Int,
    val firstError: String? = null
)

/**
 * Backs up and restores TSSL secret packages to WebDAV or S3-compatible
 * object storage. The wire format and target layout intentionally match the
 * Qt implementation: `remote-path/<digest>.tssl` and
 * `bucket/prefix/<digest>.tssl`.
 */
@Singleton
class TsslBackupService @Inject constructor(
    private val tsslStore: TsslStore,
    private val webDavRepository: WebDavRepository,
    private val clientFactory: OkHttpClientFactory
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
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): BackupResult = withContext(Dispatchers.IO) {
        val validation = validateTarget(target)
        if (validation != null) return@withContext BackupResult.Error(validation)
        if (fileNames.isEmpty()) return@withContext BackupResult.Error("No valid TSSL packages are available to back up")

        val total = fileNames.size
        var completed = 0
        onProgress(0, total)
        for (fileName in fileNames) {
            coroutineContext.ensureActive()
            val safeName = safePackageName(fileName)
                ?: return@withContext BackupResult.Error("Invalid TSSL package filename")
            val bytes = tsslStore.exportBytes(safeName)
                ?: return@withContext BackupResult.Error("Unable to read TSSL package $safeName")
            if (bytes.isEmpty() || bytes.size > MAX_PACKAGE_BYTES) {
                return@withContext BackupResult.Error("TSSL package size is invalid or exceeds 256 MiB")
            }
            val result = when (target) {
                is TsslBackupTarget.WebDav -> uploadWebDav(target, safeName, bytes)
                is TsslBackupTarget.S3 -> uploadS3(target, safeName, bytes)
            }
            result.getOrElse { return@withContext BackupResult.Error(it.message ?: "TSSL upload failed") }
            completed++
            onProgress(completed, total)
        }
        BackupResult.Success(completed)
    }

    suspend fun restore(
        target: TsslBackupTarget,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): RestoreResult = withContext(Dispatchers.IO) {
        val validation = validateTarget(target)
        if (validation != null) return@withContext RestoreResult.Error(validation)

        val remoteFiles = when (target) {
            is TsslBackupTarget.WebDav -> listWebDav(target)
            is TsslBackupTarget.S3 -> listS3(target)
        }.getOrElse { return@withContext RestoreResult.Error(it.message ?: "Unable to list TSSL backups") }

        if (remoteFiles.isEmpty()) {
            return@withContext RestoreResult.Success(TsslRestoreSummary(0, 0, 0))
        }
        var restored = 0
        var alreadyExists = 0
        var failed = 0
        var firstError: String? = null
        onProgress(0, remoteFiles.size)
        remoteFiles.forEachIndexed { index, remoteName ->
            coroutineContext.ensureActive()
            val result = when (target) {
                is TsslBackupTarget.WebDav -> downloadWebDav(target, remoteName)
                is TsslBackupTarget.S3 -> downloadS3(target, remoteName)
            }
            result.fold(
                onSuccess = { bytes ->
                    when (val imported = tsslStore.restore(bytes)) {
                        is TsslStore.RestoreResult.Stored -> restored++
                        is TsslStore.RestoreResult.AlreadyExists -> alreadyExists++
                        is TsslStore.RestoreResult.Invalid -> {
                            failed++
                            if (firstError == null) firstError = imported.reason
                        }
                    }
                },
                onFailure = { error ->
                    failed++
                    if (firstError == null) firstError = error.message
                }
            )
            onProgress(index + 1, remoteFiles.size)
        }
        RestoreResult.Success(TsslRestoreSummary(restored, alreadyExists, failed, firstError))
    }

    /** Kept for callers that back up one package directly. */
    suspend fun backupToWebDav(
        target: ServerConfig,
        fileName: String,
        bytes: ByteArray,
        onProgress: (Float) -> Unit = {}
    ): BackupResult = withContext(Dispatchers.IO) {
        if (target.serviceType != ServiceType.WEBDAV) return@withContext BackupResult.Error("Target is not a WebDAV service")
        if (bytes.isEmpty() || bytes.size > MAX_PACKAGE_BYTES) {
            return@withContext BackupResult.Error("Package is empty or exceeds the 256 MiB size limit")
        }
        val safeName = safePackageName(fileName) ?: return@withContext BackupResult.Error("Invalid TSSL package filename")
        onProgress(0f)
        uploadWebDav(TsslBackupTarget.WebDav(target, DEFAULT_REMOTE_PATH), safeName, bytes)
            .fold(
                onSuccess = { onProgress(1f); BackupResult.Success(1) },
                onFailure = { BackupResult.Error(it.message ?: "Upload failed") }
            )
    }

    private suspend fun uploadWebDav(
        target: TsslBackupTarget.WebDav,
        fileName: String,
        bytes: ByteArray
    ): Result<Unit> {
        val path = joinPath(target.path, fileName)
        return webDavRepository.upload(target.server, path, bytes)
    }

    private suspend fun listWebDav(target: TsslBackupTarget.WebDav): Result<List<String>> =
        webDavRepository.list(target.server, cleanPath(target.path)).map { items ->
            items.asSequence()
                .filter { !it.isDirectory && it.name.endsWith(TSSL_EXT, ignoreCase = true) }
                .map { it.name.substringAfterLast('/') }
                .filter { safePackageName(it) != null }
                .distinct()
                .sorted()
                .toList()
        }

    private suspend fun downloadWebDav(
        target: TsslBackupTarget.WebDav,
        fileName: String
    ): Result<ByteArray> = webDavRepository.download(target.server, joinPath(target.path, fileName))
        .mapCatching { bytes ->
            if (bytes.isEmpty() || bytes.size > MAX_PACKAGE_BYTES) {
                throw IOException("WebDAV TSSL backup file is invalid or too large")
            }
            bytes
        }

    private suspend fun uploadS3(
        target: TsslBackupTarget.S3,
        fileName: String,
        bytes: ByteArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = objectUrl(target, objectKey(target.prefix, fileName))
            val payloadHash = sha256Hex(bytes)
            val request = signedRequest(target, "PUT", url, payloadHash)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .put(bytes.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            clientFor(target).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("S3 PUT HTTP ${response.code}")
            }
        }
    }

    private suspend fun listS3(target: TsslBackupTarget.S3): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val files = mutableListOf<String>()
            var continuation: String? = null
            do {
                coroutineContext.ensureActive()
                val urlBuilder = objectUrl(target, null).newBuilder()
                    .addQueryParameter("list-type", "2")
                val prefix = cleanPrefix(target.prefix)
                if (prefix.isNotEmpty()) urlBuilder.addQueryParameter("prefix", "$prefix/")
                continuation?.let { urlBuilder.addQueryParameter("continuation-token", it) }
                val url = urlBuilder.build()
                val request = signedRequest(target, "GET", url, EMPTY_SHA256).build()
                clientFor(target).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("S3 LIST HTTP ${response.code}")
                    val page = parseS3List(response.body?.bytes() ?: throw IOException("Empty S3 listing"))
                    files += page.keys.filter { it.endsWith(TSSL_EXT, ignoreCase = true) }
                    continuation = page.nextToken.takeIf { page.truncated && it.isNotEmpty() }
                }
            } while (continuation != null)
            files.distinct().sorted()
        }
    }

    private suspend fun downloadS3(
        target: TsslBackupTarget.S3,
        key: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val url = objectUrl(target, key)
            val request = signedRequest(target, "GET", url, EMPTY_SHA256).build()
            clientFor(target).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("S3 GET HTTP ${response.code}")
                val bytes = response.body?.bytes() ?: throw IOException("Empty S3 object")
                if (bytes.isEmpty() || bytes.size > MAX_PACKAGE_BYTES) {
                    throw IOException("S3 TSSL backup file is invalid or too large")
                }
                bytes
            }
        }
    }

    private fun validateTarget(target: TsslBackupTarget): String? = when (target) {
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
        clientFactory.client(target.trustSelfSignedCertificate)

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
        var current = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> current = parser.name.orEmpty().substringAfterLast(':')
                XmlPullParser.TEXT -> when (current.lowercase()) {
                    "key" -> keys += parser.text.orEmpty()
                    "istruncated" -> truncated = parser.text.trim().equals("true", true)
                    "nextcontinuationtoken" -> nextToken = parser.text.trim()
                }
                XmlPullParser.END_TAG -> current = ""
            }
            event = parser.next()
        }
        return S3ListPage(keys, truncated, nextToken)
    }

    private data class S3ListPage(val keys: List<String>, val truncated: Boolean, val nextToken: String)

    private fun safePackageName(value: String): String? {
        val name = value.substringAfterLast('/').trim()
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
        private const val TSSL_EXT = ".tssl"
        private const val DEFAULT_REMOTE_PATH = "vibePlayerQT/tssl"
        private const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val EMPTY_SHA256 = sha256HexStatic(byteArrayOf())
        private val S3_BUCKET = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
        private val UTC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
        private val UTC_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        private fun sha256HexStatic(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
