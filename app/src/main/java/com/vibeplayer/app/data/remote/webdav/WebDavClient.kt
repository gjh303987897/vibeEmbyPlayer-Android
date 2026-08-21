package com.vibeplayer.app.data.remote.webdav

import android.util.Xml
import com.vibeplayer.app.di.OkHttpClientFactory
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.WebDavItem
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser

/**
 * Standard WebDAV client using PROPFIND / GET / PUT / MKCOL over OkHttp.
 * Basic authentication is added per-request from the repository-provided
 * password (stored securely, never logged). Mirrors the Qt WebDavClient.
 */
@Singleton
class WebDavClient @Inject constructor(
    private val clientFactory: OkHttpClientFactory
) {

    /** Returns the client tuned for this server's TLS policy (self-signed opt-in). */
    private fun clientFor(server: ServerConfig): OkHttpClient =
        clientFactory.client(server.trustSelfSignedCertificate)

    suspend fun list(server: ServerConfig, password: String, path: String): Result<List<WebDavItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(server, path)
                val body = "<?xml version=\"1.0\"?>".toRequestBody(PROPFIND_MEDIA_TYPE)
                val request = auth(server, password)
                    .url(url)
                    .method("PROPFIND", body)
                    .header("Depth", "1")
                    .build()
                clientFor(server).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("PROPFIND HTTP ${response.code}")
                    parseMultistatus(response.body?.bytes())
                }
            }
        }

    suspend fun download(server: ServerConfig, password: String, path: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveUrl(server, path)
                val request = auth(server, password).url(url).get().build()
                clientFor(server).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("GET HTTP ${response.code}")
                    response.body?.bytes() ?: throw IOException("Empty body")
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
                clientFor(server).newCall(request).execute().use { response ->
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
                clientFor(server).newCall(request).execute().use { response ->
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
            clientFor(server).newCall(request).execute().use { response ->
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
            clientFor(server).newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                    throw IOException("PUT HTTP ${response.code}")
                }
            }
            onProgress(1f)
        }
    }

    private fun auth(server: ServerConfig, password: String): Request.Builder =
        Request.Builder().header("Authorization", basicAuth(server.username, password))

    private fun basicAuth(user: String, password: String): String {
        val credentials = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        return "Basic $credentials"
    }

    private fun resolveUrl(server: ServerConfig, path: String): String {
        val base = server.normalizedBaseUrl
        val normalized = if (path.isEmpty() || path == "/") "" else "/" + path.trimStart('/')
        return base + normalized
    }

    /** Parses a PROPFIND multistatus XML response into a list of WebDavItem. */
    private fun parseMultistatus(bytes: ByteArray?): List<WebDavItem> {
        if (bytes == null) return emptyList()
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
                        val path = WebDavItem.decodePath(href ?: "")
                        val displayName = name?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
                        items += WebDavItem(
                            name = displayName,
                            path = path,
                            isDirectory = isCollection,
                            size = size,
                            contentType = contentType,
                            modifiedAt = modified
                        )
                        inResponse = false
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }

    companion object {
        private val PROPFIND_MEDIA_TYPE = "application/xml".toMediaType()
    }
}
