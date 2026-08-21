package com.vibeplayer.app.player.hls

/**
 * A running encrypted-HLS playback session. Hold a reference for the duration
 * of playback and call [close] (releasing the loopback port) when finished.
 */
class EncryptedHlsPlayback(
    private val server: EncryptedHlsServer,
    val rootManifestName: String,
    val resolvedSourceName: String?
) {
    /** Loopback URL to feed to the player, e.g. `http://127.0.0.1:PORT/index.m3u8s`. */
    val playUrl: String
        get() = server.baseUrl + rootManifestName

    /** Stops the loopback HTTP server. Safe to call more than once. */
    fun close() = server.close()
}
