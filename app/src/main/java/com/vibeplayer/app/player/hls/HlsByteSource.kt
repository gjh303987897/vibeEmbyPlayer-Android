package com.vibeplayer.app.player.hls

/**
 * Fetches raw (still-encrypted) package bytes for a path relative to the
 * package root. Implementations read local files or download from WebDAV.
 * The call may suspend (network I/O); failures are returned as failed [Result].
 */
fun interface HlsByteSource {
    suspend fun load(packageRelativePath: String): Result<ByteArray>
}
