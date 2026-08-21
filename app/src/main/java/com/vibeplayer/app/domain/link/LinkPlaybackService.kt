package com.vibeplayer.app.domain.link

import java.net.URI

/**
 * Validates and normalizes external HTTP(S) media / HLS playback URLs for the
 * built-in Link media source. Ported from the Qt LinkPlaybackService.
 *
 * Only http/https absolute URLs with a host are accepted. Embedded user info is
 * rejected. Fragments are always stripped from the playback URL (queries are
 * preserved for signed URLs); display values additionally drop the query.
 */
object LinkPlaybackService {

    private const val MAX_URL_LENGTH = 16 * 1024

    sealed class Result {
        data class Success(
            val playbackUrl: String,
            val displayName: String,
            val displayAddress: String
        ) : Result()

        sealed class Error(val message: String) : Result() {
            object Empty : Error("The URL is empty")
            object TooLong : Error("The URL is too long")
            object Invalid : Error("This is not a valid URL")
            object UnsupportedScheme : Error("Only http and https links are supported")
            object MissingHost : Error("The URL has no host")
            object EmbeddedCredentials : Error("URLs with embedded credentials are not supported")
        }
    }

    fun resolvePlaybackUrl(input: String): Result {
        val normalizedInput = input.trim()
        if (normalizedInput.isEmpty()) return Result.Error.Empty
        if (normalizedInput.length > MAX_URL_LENGTH) return Result.Error.TooLong

        val uri = runCatching { URI(normalizedInput) }.getOrNull()
            ?: return Result.Error.Invalid
        if (!uri.isAbsolute) return Result.Error.Invalid

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Error.UnsupportedScheme
        if (uri.host.isNullOrEmpty()) return Result.Error.MissingHost
        if (!uri.userInfo.isNullOrEmpty()) return Result.Error.EmbeddedCredentials

        val playbackUri = stripFragment(uri)
        return Result.Success(
            playbackUrl = playbackUri.toString(),
            displayName = displayName(uri),
            displayAddress = displayAddress(uri)
        )
    }

    private fun displayName(uri: URI): String {
        val path = uri.path.orEmpty()
        val fileName = path.substringAfterLast('/').trim()
        if (fileName.isNotEmpty()) return fileName
        val host = uri.host?.trim().orEmpty()
        return if (host.isEmpty()) "Link Playback" else host
    }

    private fun displayAddress(uri: URI): String = runCatching {
        val cleaned = StringBuffer()
        cleaned.append(uri.scheme).append("://").append(uri.rawAuthority ?: "")
        cleaned.append(uri.rawPath ?: "")
        URI(cleaned.toString()).toString()
    }.getOrDefault(uri.toString())

    private fun stripFragment(uri: URI): URI = runCatching {
        URI(
            uri.scheme,
            uri.rawUserInfo,
            uri.host,
            uri.port,
            uri.rawPath,
            uri.rawQuery,
            null
        )
    }.getOrDefault(uri)
}
