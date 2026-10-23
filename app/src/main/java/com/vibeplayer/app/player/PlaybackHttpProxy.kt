package com.vibeplayer.app.player

import com.vibeplayer.app.util.UserErrorKind
import com.vibeplayer.app.util.UserMessageFormatter
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import java.io.StringReader
import java.io.StringWriter
import org.xml.sax.InputSource
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Keeps IJK's HTTP reader behind the app's authenticated, certificate-checked transport. */
internal class PlaybackHttpProxy(
    private val client: OkHttpClient,
    private val origin: HttpUrl,
    headers: Map<String, String>,
    private val selectedHlsVariant: HlsVariant? = null
) : Closeable {
    private val headers = headers.toMap()
    private val received = java.util.concurrent.atomic.AtomicLong(0L)
    val receivedBytes: Long get() = received.get()
    private val transport = client.newBuilder().apply { networkInterceptors().add(0, okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.url.host == origin.host && request.url.port == origin.port && request.url.scheme == origin.scheme) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().apply { this@PlaybackHttpProxy.headers.keys.forEach { removeHeader(it) } }.build())
        }
    }) }.build()
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val slots = Semaphore(32)
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "ijk-http").apply { isDaemon = true }
    }
    private val token = UUID.randomUUID().toString()
    private val resources = object : LinkedHashMap<String, HttpUrl>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HttpUrl>): Boolean = size > 8192
    }
    private val routes = HashMap<HttpUrl, String>()
    private val dashOrigins = HashMap<String, HttpUrl>()
    val playbackUrl: String = register(origin)
    private val playbackPath = playbackUrl.toHttpUrl().encodedPath

    /** Keep transport failures distinct from IJK's generic open-input error. No URLs or secrets. */
    @Volatile
    var failure: UserErrorKind? = null
        private set

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) {
                    socket.close()
                    continue
                }
                connections.add(socket)
                try {
                    workers.execute {
                        try { handle(socket) } finally {
                            connections.remove(socket)
                            slots.release()
                            socket.close()
                        }
                    }
                } catch (_: Exception) {
                    connections.remove(socket)
                    slots.release()
                    socket.close()
                }
            }
        }, "ijk-http-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    private fun register(url: HttpUrl): String {
        val existing = routes[url]
        if (existing != null && resources[existing] != null) return localUrl(existing)
        val id = UUID.randomUUID().toString() + "/" + url.encodedPath.substringAfterLast('/').ifEmpty { "stream" }
        resources[id] = url
        // Live playlists create new segment URLs continuously; keep the reverse index bounded too.
        if (routes.size >= 8192) routes.entries.removeAll { resources[it.value] == null }
        routes[url] = id
        return localUrl(id)
    }

    private fun localUrl(id: String) = "http://127.0.0.1:${server.localPort}/$token/$id"

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val output = socket.getOutputStream()
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine()?.split(' ') ?: return
            if (requestLine.size != 3 || requestLine[0] !in listOf("GET", "HEAD")) {
                output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val path = requestLine[1].substringBefore('?')
            val resourcePath = path.removePrefix("/$token/")
            val url = synchronized(this) {
                if (path == playbackPath) origin
                else resources[resourcePath] ?: dashOrigins[resourcePath.substringBefore('/')]?.let { base ->
                    base.resolve(requestLine[1].removePrefix("/$token/").substringAfter('/'))
                        ?.takeIf { it.host == base.host && it.port == base.port && it.scheme == base.scheme }
                }
            }
            if (!path.startsWith("/$token/") || url == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            var range: String? = null
            var bytesRead = 0
            while (true) {
                val line = reader.readLine() ?: break
                bytesRead += line.length
                require(bytesRead <= 32_768)
                if (line.isEmpty()) break
                if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
            }
            val request = Request.Builder().url(url).method(requestLine[0], null)
            if (url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) {
                headers.forEach { (name, value) -> request.header(name, value) }
            }
            request.header("Accept-Encoding", "identity")
            range?.let { request.header("Range", it) }
            val call = transport.newCall(request.build())
            calls.add(call)
            try {
                val upstreamResponse = try {
                    call.execute()
                } catch (error: Exception) {
                    failure = UserMessageFormatter.classify(error).kind
                    throw error
                }
                upstreamResponse.use { response ->
                    if (!response.isSuccessful) {
                        failure = UserMessageFormatter.classify("HTTP ${response.code}").kind
                    }
                    val body = response.body
                    val peek = body?.source()?.peek()
                    val isPlaylist = requestLine[0] != "HEAD" && response.isSuccessful && peek != null &&
                        peek.request(7) && peek.readUtf8(7) == "#EXTM3U"
                    val isDash = requestLine[0] != "HEAD" && response.isSuccessful &&
                        (response.header("Content-Type").orEmpty().contains("dash+xml") || response.request.url.encodedPath.endsWith(".mpd"))
                    val rewritten = if (isPlaylist || isDash) {
                        require(body != null && body.contentLength() <= MAX_PLAYLIST_BYTES)
                        val source = body.source()
                        require(!source.request(MAX_PLAYLIST_BYTES + 1)) { "Playlist too large" }
                        val text = source.readUtf8()
                        received.addAndGet(text.toByteArray(StandardCharsets.UTF_8).size.toLong())
                        (if (isDash) rewriteDash(text, response.request.url) else rewritePlaylist(
                            selectHlsVariant(text, response.request.url, selectedHlsVariant), response.request.url))
                            .toByteArray(StandardCharsets.UTF_8)
                    } else null
                    val responseHeaders = buildString {
                        append("HTTP/1.1 ${response.code} ${response.message}\r\n")
                        append("Connection: close\r\n")
                        append("Content-Type: ${if (isDash) "application/dash+xml" else if (rewritten != null) "application/vnd.apple.mpegurl" else response.header("Content-Type") ?: "application/octet-stream"}\r\n")
                        val length = rewritten?.size?.toLong() ?: response.header("Content-Length")?.toLongOrNull()
                        if (length != null) append("Content-Length: $length\r\n")
                        if (rewritten == null) {
                            response.header("Content-Range")?.let { append("Content-Range: $it\r\n") }
                            response.header("Accept-Ranges")?.let { append("Accept-Ranges: $it\r\n") }
                        }
                        append("\r\n")
                    }
                    output.write(responseHeaders.toByteArray(StandardCharsets.ISO_8859_1))
                    if (requestLine[0] != "HEAD") {
                        if (rewritten != null) output.write(rewritten)
                        else body?.byteStream()?.let { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                received.addAndGet(count.toLong())
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    output.flush()
                }
            } finally {
                calls.remove(call)
            }
        } catch (_: Exception) {
            runCatching {
                output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            }
        }
    }

    private fun rewritePlaylist(text: String, base: HttpUrl): String = text.lineSequence().joinToString("\n") { line ->
        if (line.startsWith('#')) {
            URI_ATTRIBUTE.replace(line) { match ->
                val url = base.resolve(match.groupValues[1]) ?: error("Invalid HLS resource URL")
                "URI=\"${register(url)}\""
            }
        } else if (line.isNotBlank()) {
            register(base.resolve(line.trim()) ?: error("Invalid HLS resource URL"))
        } else line
    }

    @Synchronized
    private fun dashUrl(url: HttpUrl): String {
        val remoteOrigin = url.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val id = dashOrigins.entries.firstOrNull { it.value == remoteOrigin }?.key
            ?: UUID.randomUUID().toString().also { dashOrigins[it] = remoteOrigin }
        return localUrl(id) + url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
    }

    private fun rewriteDash(text: String, base: HttpUrl): String {
        require(!text.contains("<!DOCTYPE", ignoreCase = true)) { "DASH document types are not supported" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        val document = builder.parse(InputSource(StringReader(text)))
        fun visit(element: Element, parentBase: HttpUrl) {
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            val bases = children.filter { it.localName == "BaseURL" }
            val effectiveBase = bases.firstOrNull()?.textContent?.trim()?.let { parentBase.resolve(it) } ?: parentBase
            bases.forEach { node ->
                node.textContent = dashUrl(parentBase.resolve(node.textContent.trim()) ?: error("Invalid DASH base URL"))
            }
            for (name in listOf("media", "initialization", "sourceURL", "index")) {
                val value = element.getAttribute(name)
                if (value.startsWith('/') || value.startsWith("http://") || value.startsWith("https://")) {
                    element.setAttribute(name, dashUrl(effectiveBase.resolve(value) ?: error("Invalid DASH resource URL")))
                }
            }
            children.filter { it.localName != "BaseURL" }.forEach { child ->
                if (child.localName == "Location") {
                    child.textContent = register(parentBase.resolve(child.textContent.trim()) ?: error("Invalid DASH location"))
                } else visit(child, effectiveBase)
            }
        }
        visit(document.documentElement, base)
        val root = document.documentElement
        if ((0 until root.childNodes.length).none { (root.childNodes.item(it) as? Element)?.localName == "BaseURL" }) {
            val node = document.createElementNS(root.namespaceURI, "BaseURL")
            node.textContent = dashUrl(base.resolve(".") ?: error("Invalid DASH manifest URL"))
            root.insertBefore(node, root.firstChild)
        }
        val output = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(output))
        return output.toString()
    }

    override fun close() {
        server.close()
        calls.forEach { it.cancel() }
        connections.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
        val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
    }
}
