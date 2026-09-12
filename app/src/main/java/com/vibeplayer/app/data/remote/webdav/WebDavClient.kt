package com.vibeplayer.app.data.remote.webdav

import android.util.Xml
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.WebDavItem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser

/**
 * Standard WebDAV client using PROPFIND / GET / PUT / MKCOL over OkHttp.
 * Basic authentication is added per-request from the repository-provided
 * password (stored securely, never logged). Mirrors the Qt WebDavClient.
 */
data class WebDavInitialRange(val bytes: ByteArray, val totalLength: Long, val etag: String)

/**
 * Leading bytes of a remote object and whether the whole object fits in them.
 * Used for bounded metadata reads so a listing never transfers a large object.
 */
data class WebDavPrefix(val bytes: ByteArray, val totalLength: Long, val complete: Boolean)

@Singleton
class WebDavClient @Inject constructor(
    private val clientFactory: OkHttpClientFactory
) {

    /** Returns a client tuned for TLS and the server's authentication scheme. */
    private fun clientFor(server: ServerConfig, password: String? = null): OkHttpClient {
        val base = clientFactory.client(server.trustSelfSignedCertificate)
        return if (password == null) base else base.newBuilder()
            // Basic is sent pre-emptively by [auth]. The authenticator is a
            // fallback for WebDAV servers that challenge with RFC 7616 Digest.
            .authenticator(DigestAuthenticator(server.username, password))
            .build()
    }

    suspend fun list(server: ServerConfig, password: String, path: String): Result<List<WebDavItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // WebDAV collection URLs should use the trailing-slash form.
                // More importantly, PROPFIND requires either an omitted body or
                // a valid <propfind> document; an XML declaration by itself is
                // rejected as malformed by strict servers (HTTP 400).
                val url = resolveDirectoryUrl(server, path)
                val body = PROPFIND_XML.toRequestBody(PROPFIND_MEDIA_TYPE)
                val request = auth(server, password)
                    .url(url)
                    .method("PROPFIND", body)
                    .header("Depth", "1")
                    .build()
                clientFor(server, password).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("PROPFIND HTTP ${response.code}")
                    parseMultistatus(response.body?.bytes(), server, url)
                }
            }
        }

    suspend fun download(server: ServerConfig, password: String, path: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(server, path)
                val request = auth(server, password).url(url).get().build()
                clientFor(server, password).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GET HTTP ${response.code}")
                    response.body?.bytes() ?: throw IOException("Empty body")
                }
            }
        }

    /** Starts M3U8SP with one bounded Range request, matching the Qt protocol. */
    suspend fun downloadInitialRange(
        server: ServerConfig,
        password: String,
        path: String,
        maximumLength: Long
    ): Result<WebDavInitialRange> = withContext(Dispatchers.IO) {
        runCatching {
            require(maximumLength > 0)
            val request = auth(server, password)
                .url(resolveUrl(server, path))
                .header("Range", "bytes=0-${maximumLength - 1}")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            clientFor(server, password).newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Server did not honor byte range (HTTP ${response.code})")
                val match = response.header("Content-Range")
                    ?.let { Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(it) }
                    ?: throw IOException("Invalid Content-Range")
                val start = match.groupValues[1].toLong()
                val end = match.groupValues[2].toLong()
                val total = match.groupValues[3].toLong()
                if (start != 0L || end < start || end >= total || end >= maximumLength) {
                    throw IOException("Content-Range does not match initial request")
                }
                val etag = response.header("ETag")?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Remote M3U8SP object has no stable ETag")
                val bytes = response.body?.bytes() ?: throw IOException("Empty range body")
                if (bytes.size.toLong() != end + 1) throw IOException("Truncated range body")
                WebDavInitialRange(bytes, total, etag)
            }
        }
    }

    /**
     * Reads at most [maximumLength] leading bytes. A server that ignores the Range
     * request is still accepted: the body is then capped and reported incomplete,
     * so metadata lookups never pull a whole media object into memory.
     */
    suspend fun downloadPrefix(
        server: ServerConfig,
        password: String,
        path: String,
        maximumLength: Long
    ): Result<WebDavPrefix> = withContext(Dispatchers.IO) {
        runCatching {
            require(maximumLength > 0) { "Invalid prefix length" }
            val request = auth(server, password)
                .url(resolveUrl(server, path))
                .header("Range", "bytes=0-${maximumLength - 1}")
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            clientFor(server, password).newCall(request).execute().use { response ->
                when (response.code) {
                    206 -> {
                        val match = response.header("Content-Range")
                            ?.let { Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(it) }
                            ?: throw IOException("Invalid Content-Range")
                        val start = match.groupValues[1].toLong()
                        val end = match.groupValues[2].toLong()
                        val total = match.groupValues[3].toLong()
                        if (start != 0L || end < start || end >= total || end >= maximumLength) {
                            throw IOException("Content-Range does not match prefix request")
                        }
                        val bytes = response.body?.bytes() ?: throw IOException("Empty prefix body")
                        if (bytes.size.toLong() != end + 1) throw IOException("Truncated prefix body")
                        WebDavPrefix(bytes, total, complete = total <= bytes.size)
                    }
                    200 -> {
                        val declared = response.body?.contentLength() ?: -1L
                        val bytes = response.body?.let { body ->
                            val input = body.byteStream()
                            val buffer = java.io.ByteArrayOutputStream(minOf(maximumLength, 64L * 1024L).toInt())
                            val chunk = ByteArray(8 * 1024)
                            var remaining = maximumLength
                            while (remaining > 0) {
                                val read = input.read(chunk, 0, minOf(remaining, chunk.size.toLong()).toInt())
                                if (read < 0) break
                                buffer.write(chunk, 0, read)
                                remaining -= read
                            }
                            buffer.toByteArray()
                        } ?: throw IOException("Empty prefix body")
                        if (bytes.isEmpty()) throw IOException("Empty prefix body")
                        val total = if (declared > 0) declared else bytes.size.toLong()
                        WebDavPrefix(bytes, total, complete = bytes.size.toLong() < maximumLength ||
                            (declared > 0 && declared <= maximumLength))
                    }
                    else -> throw IOException("GET HTTP ${response.code}")
                }
            }
        }
    }

    /** Reads one exact byte range. M3U8SP rejects servers that ignore Range with HTTP 200. */
    suspend fun downloadRange(
        server: ServerConfig,
        password: String,
        path: String,
        offset: Long,
        length: Long,
        expectedTotalLength: Long,
        etag: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(offset >= 0 && length > 0) { "Invalid byte range" }
            val end = Math.addExact(offset, length - 1)
            val request = auth(server, password)
                .url(resolveUrl(server, path))
                .header("Range", "bytes=$offset-$end")
                .header("If-Range", etag)
                .header("Accept-Encoding", "identity")
                .get()
                .build()
            clientFor(server, password).newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Server did not honor byte range (HTTP ${response.code})")
                val contentRange = response.header("Content-Range")
                    ?: throw IOException("Missing Content-Range")
                val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(contentRange)
                    ?: throw IOException("Invalid Content-Range")
                if (match.groupValues[1].toLong() != offset ||
                    match.groupValues[2].toLong() != end ||
                    match.groupValues[3].toLong() != expectedTotalLength
                ) throw IOException("Content-Range does not match request")
                if (response.header("ETag") != etag) throw IOException("Remote M3U8SP object changed")
                val bytes = response.body?.bytes() ?: throw IOException("Empty range body")
                if (bytes.size.toLong() != length) throw IOException("Truncated range body")
                bytes
            }
        }
    }

    suspend fun upload(server: ServerConfig, password: String, path: String, bytes: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(server, path)
                val request = auth(server, password)
                    .url(url)
                    .put(bytes.toRequestBody())
                    .build()
                clientFor(server, password).newCall(request).execute().use { response ->
                    if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                        throw IOException("PUT HTTP ${response.code}")
                    }
                }
            }
        }

    suspend fun createDirectory(server: ServerConfig, password: String, path: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(server, path)
                val request = auth(server, password)
                    .url(url)
                    .method("MKCOL", null)
                    .build()
                clientFor(server, password).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("MKCOL HTTP ${response.code}")
                }
            }
        }

    /**
     * Streams a remote file into [output]. Progress (fraction 0..1) is reported
     * as bytes are copied; [isCancelled] is checked between reads so callers can
     * abort cleanly. The caller owns [output] and must close it.
     */
    suspend fun downloadTo(
        server: ServerConfig,
        password: String,
        path: String,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(server, path)
            val request = auth(server, password).url(url).get().build()
            clientFor(server, password).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GET HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty body")
                if (body.contentLength() > 0) onProgress(0f)
                val buf = ByteArray(64 * 1024)
                var transferred = 0L
                body.source().inputStream().use { input ->
                    while (true) {
                        if (isCancelled()) throw IOException("Cancelled")
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        transferred += read
                        onProgress(if (totalBytes > 0) (transferred.toFloat() / totalBytes) else 0f)
                    }
                }
                output.flush()
                onProgress(1f)
            }
        }
    }

    /**
     * Streams [input] to a remote path with a PUT request. Progress is reported
     * for the already-known [totalBytes]; [isCancelled] is checked between reads.
     * The caller owns [input] and must close it.
     */
    suspend fun uploadFrom(
        server: ServerConfig,
        password: String,
        path: String,
        input: InputStream,
        totalBytes: Long,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveUrl(server, path)
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = totalBytes
                override fun writeTo(sink: okio.BufferedSink) {
                    val buf = ByteArray(64 * 1024)
                    var transferred = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("Cancelled")
                        val read = input.read(buf)
                        if (read < 0) break
                        sink.write(buf, 0, read)
                        transferred += read
                        onProgress(if (totalBytes > 0) (transferred.toFloat() / totalBytes) else 0f)
                    }
                }
            }
            val request = auth(server, password).url(url).put(body).build()
            clientFor(server, password).newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                    throw IOException("PUT HTTP ${response.code}")
                }
            }
            onProgress(1f)
        }
    }

    private fun auth(server: ServerConfig, password: String): Request.Builder =
        Request.Builder().apply {
            if (server.username.isNotBlank() || password.isNotBlank()) {
                header("Authorization", basicAuth(server.username, password))
            }
        }

    private fun basicAuth(user: String, password: String): String {
        val credentials = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        return "Basic $credentials"
    }

    /** Resolves a path beneath the configured WebDAV base URL. */
    private fun resolveUrl(server: ServerConfig, path: String): String {
        val base = server.normalizedBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid WebDAV URL")
        val relative = path.trim().trim('/')
        if (relative.isEmpty()) return base.toString()
        return base.newBuilder().addPathSegments(relative).build().toString()
    }

    private fun resolveDirectoryUrl(server: ServerConfig, path: String): String {
        val url = resolveUrl(server, path)
        return if (url.endsWith('/')) url else "$url/"
    }

    /** Parses a PROPFIND multistatus XML response into paths relative to the service base. */
    private fun parseMultistatus(
        bytes: ByteArray?,
        server: ServerConfig,
        requestUrl: String
    ): List<WebDavItem> {
        if (bytes == null) return emptyList()
        val baseUrl = server.normalizedBaseUrl.toHttpUrlOrNull() ?: return emptyList()
        val currentUrl = requestUrl.toHttpUrlOrNull() ?: return emptyList()
        val items = mutableListOf<WebDavItem>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(bytes.inputStream(), "UTF-8")

        var href: String? = null
        var name: String? = null
        var size: Long = 0L
        var contentType = ""
        var modified = ""
        var isCollection = false
        var inResponse = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name ?: ""
                    when (tag) {
                        "response" -> { inResponse = true; href = null; name = null; size = 0L; contentType = ""; modified = ""; isCollection = false }
                        "href" -> href = parser.nextText()
                        "displayname" -> name = parser.nextText()
                        "getcontentlength" -> size = parser.nextText()?.toLongOrNull() ?: 0L
                        "getcontenttype" -> contentType = parser.nextText() ?: ""
                        "getlastmodified" -> modified = parser.nextText() ?: ""
                        "collection" -> isCollection = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    if (tag == "response" && inResponse) {
                        val itemUrl = currentUrl.resolve(href?.trim().orEmpty())
                        if (itemUrl != null && sameAuthority(baseUrl, itemUrl) &&
                            itemUrl.encodedPath.trimEnd('/') != currentUrl.encodedPath.trimEnd('/')) {
                            val path = relativePath(baseUrl, itemUrl)
                            if (path != null) {
                                val displayName = name?.trim()?.takeIf { it.isNotBlank() }
                                    ?: itemUrl.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
                                items += WebDavItem(
                                    name = displayName,
                                    path = path,
                                    isDirectory = isCollection,
                                    size = size,
                                    contentType = contentType,
                                    modifiedAt = modified
                                )
                            }
                        }
                        inResponse = false
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    private fun sameAuthority(base: HttpUrl, item: HttpUrl): Boolean =
        base.scheme.equals(item.scheme, ignoreCase = true) &&
            base.host.equals(item.host, ignoreCase = true) && base.port == item.port

    /** Returns a decoded path relative to the configured service base path. */
    private fun relativePath(base: HttpUrl, item: HttpUrl): String? {
        val basePath = base.encodedPath.trimEnd('/')
        val itemPath = item.encodedPath.trimEnd('/')
        val relativeEncoded = if (basePath.isEmpty()) {
            itemPath.trimStart('/')
        } else {
            when {
                itemPath == basePath -> ""
                itemPath.startsWith("$basePath/") -> itemPath.removePrefix("$basePath/")
                else -> return null
            }
        }
        return relativeEncoded.split('/').filter { it.isNotEmpty() }
            .joinToString("/") { android.net.Uri.decode(it) }
    }

    /** Handles RFC 7616 Digest challenges while retaining pre-emptive Basic auth. */
    private class DigestAuthenticator(
        private val username: String,
        private val password: String
    ) : Authenticator {
        private val nonceCounts = mutableMapOf<String, Int>()

        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.code != 401 || username.isBlank() ||
                response.request.header("Authorization")?.startsWith("Digest ") == true ||
                responseCount(response) >= 3
            ) return null
            val challenge = response.headers("WWW-Authenticate")
                .firstOrNull { it.trimStart().startsWith("Digest", ignoreCase = true) }
                ?: return null
            val values = parseChallenge(challenge)
            val realm = values["realm"] ?: return null
            val nonce = values["nonce"] ?: return null
            val algorithm = values["algorithm"]?.uppercase(Locale.US) ?: "MD5"
            val qop = values["qop"]?.split(',')?.map { it.trim().lowercase(Locale.US) }
                ?.firstOrNull { it == "auth" }
            if (values["qop"] != null && qop == null) return null
            val hashAlgorithm = when {
                algorithm.startsWith("SHA-256") -> "SHA-256"
                algorithm.startsWith("MD5") -> "MD5"
                else -> return null
            }
            val cnonce = UUID.randomUUID().toString().replace("-", "")
            val nonceCount = synchronized(nonceCounts) {
                val next = (nonceCounts[nonce] ?: 0) + 1
                nonceCounts[nonce] = next
                "%08x".format(Locale.US, next)
            }
            val uri = response.request.url.encodedPath +
                response.request.url.encodedQuery?.let { "?$it" }.orEmpty()
            var ha1 = digest(hashAlgorithm, "$username:$realm:$password")
            if (algorithm.endsWith("-SESS")) {
                ha1 = digest(hashAlgorithm, "$ha1:$nonce:$cnonce")
            }
            val ha2 = digest(hashAlgorithm, "${response.request.method}:$uri")
            val responseDigest = if (qop == null) {
                digest(hashAlgorithm, "$ha1:$nonce:$ha2")
            } else {
                digest(hashAlgorithm, "$ha1:$nonce:$nonceCount:$cnonce:$qop:$ha2")
            }
            val header = buildString {
                append("Digest username=\"").append(escape(username)).append("\"")
                append(", realm=\"").append(escape(realm)).append("\"")
                append(", nonce=\"").append(escape(nonce)).append("\"")
                append(", uri=\"").append(escape(uri)).append("\"")
                append(", response=\"").append(responseDigest).append("\"")
                if (values["algorithm"] != null) append(", algorithm=").append(algorithm)
                if (qop != null) {
                    append(", qop=").append(qop)
                    append(", nc=").append(nonceCount)
                    append(", cnonce=\"").append(cnonce).append("\"")
                }
                values["opaque"]?.let { append(", opaque=\"").append(escape(it)).append("\"") }
            }
            return response.request.newBuilder().header("Authorization", header).build()
        }

        private fun parseChallenge(header: String): Map<String, String> {
            val body = header.substringAfter(' ', "")
            val regex = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*(?:\"([^\"]*)\"|([^,\s]+))""")
            return regex.findAll(body).associate { match ->
                match.groupValues[1].lowercase(Locale.US) to
                    (match.groupValues[2].ifEmpty { match.groupValues[3] })
            }
        }

        private fun digest(algorithm: String, value: String): String =
            MessageDigest.getInstance(algorithm).digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

        private fun escape(value: String): String =
            value.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun responseCount(response: Response): Int {
            var count = 1
            var prior = response.priorResponse
            while (prior != null) {
                count++
                prior = prior.priorResponse
            }
            return count
        }
    }

    companion object {
        private val PROPFIND_XML = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
              <D:prop>
                <D:displayname/>
                <D:resourcetype/>
                <D:getcontentlength/>
                <D:getcontenttype/>
                <D:getlastmodified/>
            </D:prop>
            </D:propfind>
        """.trimIndent()
        private val PROPFIND_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }
}
