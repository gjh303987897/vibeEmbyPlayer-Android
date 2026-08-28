package com.vibeplayer.app.player.hls

import com.vibeplayer.app.domain.tssl.TsslCrypto
import com.vibeplayer.app.domain.tssl.TsslDocument
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Minimal loopback-only HTTP server that exposes an encrypted-HLS package to
 * the Media3/ExoPlayer HLS pipeline.
 *
 * The verified root manifest is served at the virtual [rootManifestName] path
 * (chosen to end in `.m3u8` so Media3 treats it as an HLS stream). [source]
 * fetches raw (still-encrypted) child segment and auxiliary resources by their
 * package-relative path; [document] supplies the TSSL keys and integrity data.
 *
 * `.ts` segments are authenticated (AES-256-GCM) and decrypted in memory before
 * plaintext is written out, so ciphertext never reaches the player. Child
 * playlists and resources are verified against the TSSL digests.
 *
 * The server binds only to 127.0.0.1 and stops when [close] is called.
 */
class EncryptedHlsServer(
    private val document: TsslDocument,
    private val source: HlsByteSource,
    private val rootManifestName: String,
    private val rootManifestBytes: ByteArray
) {

    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null

    val baseUrl: String
        get() = serverSocket?.let { "http://127.0.0.1:${it.localPort}/" } ?: ""

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        val pool = Executors.newFixedThreadPool(4)
        executor = pool
        Thread {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }.start()
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || (parts[0] != "GET" && parts[0] != "HEAD")) {
                    respond(s.getOutputStream(), 405, "text/plain", "Method Not Allowed".toByteArray())
                    return
                }
                val target = parts[1]
                var rangeHeader: String? = null
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = header.substringAfter(':').trim()
                    }
                }
                val rawPath = target.substringBefore('?')
                val rel = normalizePath(rawPath.substringBefore('#'))
                if (rel == null) {
                    respond(s.getOutputStream(), 400, "text/plain", "Bad path".toByteArray())
                    return
                }
                serve(s.getOutputStream(), rel, parts[0] == "HEAD", rangeHeader)
            } catch (_: Exception) {
                try { respond(s.getOutputStream(), 500, "text/plain", "Internal Error".toByteArray()) } catch (_: Exception) {}
            }
        }
    }

    private fun serve(out: OutputStream, rel: String, headOnly: Boolean, rangeHeader: String?) {
        // Root manifest (pre-verified by the manager) served at its virtual name.
        if (rel == rootManifestName) {
            respondVerified(out, "application/vnd.apple.mpegurl", rootManifestBytes, headOnly, rangeHeader)
            return
        }
        // Child playlists must be registered and digest-verified.
        val childPlaylist = document.manifests.firstOrNull { it.path == rel }
        if (childPlaylist != null) {
            val bytes = loadOrNull(rel) ?: return respond404(out)
            if (TsslCrypto.sha256Hex(bytes) != childPlaylist.sha256 ||
                !validateHlsPlaylist(bytes, childPlaylist.path, document, root = false)
            ) {
                respond(out, 403, "text/plain", "Verification failed".toByteArray()); return
            }
            respondVerified(out, "application/vnd.apple.mpegurl", bytes, headOnly, rangeHeader)
            return
        }
        // Encrypted segments.
        val segment = document.segments.firstOrNull { it.path == rel }
        if (segment != null) {
            val encrypted = loadOrNull(rel) ?: return respond404(out)
            val plain = try {
                TsslCrypto.decrypt(segment.key, encrypted)
            } catch (_: Exception) {
                respond(out, 500, "text/plain", "Authenticated decryption failed".toByteArray()); return
            }
            respondVerified(out, "video/mp2t", plain, headOnly, rangeHeader)
            return
        }
        // Authenticated auxiliary resources (e.g. subtitles).
        if (document.resources.containsKey(rel)) {
            val bytes = loadOrNull(rel) ?: return respond404(out)
            val expected = document.resources[rel] ?: ""
            if (TsslCrypto.sha256Hex(bytes) != expected) {
                respond(out, 403, "text/plain", "Verification failed".toByteArray()); return
            }
            respondVerified(out, "text/plain", bytes, headOnly, rangeHeader)
            return
        }
        respond404(out)
    }

    private fun loadOrNull(rel: String): ByteArray? =
        try { runBlocking { source.load(rel).getOrNull() } } catch (_: Exception) { null }

    /**
     * Normalizes the request target into a package-relative path. The leading
     * virtual-root slash of an HTTP request path is stripped; absolute links,
     * backslashes, escape sequences, and dot-segments are rejected.
     */
    private fun normalizePath(raw: String): String? {
        if (raw.contains('\\')) return null
        if (raw.contains("://")) return null
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
        var p = decoded
        if (p.startsWith("/")) p = p.substring(1)
        if (p.startsWith("/") || p.isEmpty()) return null
        if (p.startsWith("://")) return null
        val segments = p.split('/')
        for (seg in segments) {
            if (seg == ".." || seg == "." || seg.isEmpty()) return null
        }
        return segments.joinToString("/")
    }

    private fun respondVerified(out: OutputStream, contentType: String, body: ByteArray, headOnly: Boolean, rangeHeader: String?) {
        val range = rangeHeader?.let { parseHttpByteRange(it, body.size.toLong()) }
        if (rangeHeader != null && range == null) {
            respond(out, 416, contentType, ByteArray(0), true, body.size.toLong())
            return
        }
        if (range != null) {
            respond(out, 206, contentType, body.copyOfRange(range.first.toInt(), range.last.toInt() + 1), headOnly, body.size.toLong(), range)
        } else respond(out, 200, contentType, body, headOnly, body.size.toLong())
    }

    private fun respond404(out: OutputStream) { respond(out, 404, "text/plain", "Not found".toByteArray()) }

    private fun respond(out: OutputStream, code: Int, contentType: String, body: ByteArray, headOnly: Boolean = false, totalLength: Long = body.size.toLong(), range: LongRange? = null) {
        try {
            val reason = when (code) {
                200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 403 -> "Forbidden"
                404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
                else -> "OK"
            }
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (range != null) append("Content-Range: bytes ${range.first}-${range.last}/$totalLength\r\n")
                else if (code == 416) append("Content-Range: bytes */$totalLength\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(StandardCharsets.US_ASCII))
            if (!headOnly) out.write(body)
            out.flush()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        executor?.shutdownNow()
        executor = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}

internal fun parseHttpByteRange(value: String, totalLength: Long): LongRange? = runCatching {
    require(totalLength > 0)
    val match = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(value) ?: return null
    val startText = match.groupValues[1]
    val endText = match.groupValues[2]
    require(startText.isNotEmpty() || endText.isNotEmpty())
    val start: Long
    val end: Long
    if (startText.isEmpty()) {
        val suffix = endText.toLong()
        require(suffix > 0)
        start = (totalLength - suffix).coerceAtLeast(0)
        end = totalLength - 1
    } else {
        start = startText.toLong()
        require(start in 0 until totalLength)
        end = if (endText.isEmpty()) totalLength - 1 else endText.toLong().coerceAtMost(totalLength - 1)
        require(end >= start)
    }
    start..end
}.getOrNull()
