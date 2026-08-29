package com.vibeplayer.app.util

import com.vibeplayer.app.data.remote.NetworkErrorKind
import com.vibeplayer.app.data.remote.NetworkException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** A small, stable set of categories that the UI can translate consistently. */
enum class UserErrorKind {
    NETWORK,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    TIMEOUT,
    INVALID_INPUT,
    REQUEST_REJECTED,
    SERVER,
    FORMAT,
    UNKNOWN
}

data class UserErrorClassification(
    val kind: UserErrorKind,
    /** A short safe detail, only populated when it is useful to a person. */
    val detail: String? = null
)

/**
 * Converts implementation exceptions and protocol status text into a small
 * number of user-oriented categories. Raw exception messages often contain
 * URLs, paths, or parser internals, so callers should render [classify] rather
 * than displaying the exception directly.
 */
object UserMessageFormatter {

    private val statusPattern = Regex("\\b(?:HTTP(?:\\s+status)?|status(?:\\s+code)?)\\s*[:=]?\\s*(\\d{3})\\b", RegexOption.IGNORE_CASE)
    private val bareStatusPattern = Regex("\\b(4\\d{2}|5\\d{2})\\b")
    private val credentialsPattern = Regex("(?i)(https?://)([^/\\s:@]+):([^@/\\s]+)@")
    private val querySecretPattern = Regex("(?i)([?&](?:api[_-]?key|token|password|secret|access[_-]?token)=)[^&\\s]+")
    private val whitespacePattern = Regex("\\s+")

    fun classify(error: Throwable?): UserErrorClassification {
        if (error == null) return UserErrorClassification(UserErrorKind.UNKNOWN)

        if (error is NetworkException) {
            val statusKind = classifyStatus(error.statusCode)
            if (statusKind != null) return UserErrorClassification(statusKind)
            return when (error.kind) {
                NetworkErrorKind.INVALID_URL -> UserErrorClassification(UserErrorKind.INVALID_INPUT)
                NetworkErrorKind.PARSE -> {
                    val messageKind = classify(error.message)
                    if (messageKind.kind != UserErrorKind.UNKNOWN) messageKind
                    else UserErrorClassification(UserErrorKind.FORMAT)
                }
                NetworkErrorKind.HTTP -> classify(error.message)
                NetworkErrorKind.NETWORK -> classify(error.cause ?: error, inspectCause = false)
                NetworkErrorKind.UNKNOWN -> classify(error.message)
            }
        }

        return classify(error, inspectCause = true)
    }

    fun classify(raw: String?): UserErrorClassification {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return UserErrorClassification(UserErrorKind.UNKNOWN)

        val status = statusPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: bareStatusPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        classifyStatus(status)?.let { return UserErrorClassification(it) }

        val lower = text.lowercase()
        val kind = when {
            "expected start of array" in lower ||
                "json" in lower || "cbor" in lower || "parse" in lower ||
                "malformed" in lower || "unsupported format" in lower -> UserErrorKind.FORMAT
            "unauthorized" in lower || "authentication" in lower ||
                "sign in" in lower || "login" in lower || "password not set" in lower ->
                UserErrorKind.AUTHENTICATION
            "forbidden" in lower || "permission" in lower ||
                "access denied" in lower || "no permission" in lower -> UserErrorKind.PERMISSION
            "not found" in lower || "missing" in lower || "cannot be found" in lower ->
                UserErrorKind.NOT_FOUND
            "timed out" in lower || "timeout" in lower -> UserErrorKind.TIMEOUT
            "invalid" in lower || "required" in lower || "empty" in lower ||
                "unsupported scheme" in lower || "has no host" in lower ->
                UserErrorKind.INVALID_INPUT
            "http 400" in lower || "bad request" in lower ||
                "request rejected" in lower -> UserErrorKind.REQUEST_REJECTED
            "server" in lower || "refused" in lower || "failed to load" in lower ||
                "playback failed" in lower -> UserErrorKind.SERVER
            "network" in lower || "connect" in lower || "unreachable" in lower ||
                "host" in lower || "ssl" in lower || "certificate" in lower ->
                UserErrorKind.NETWORK
            else -> UserErrorKind.UNKNOWN
        }
        return UserErrorClassification(kind, safeDetail(text))
    }

    /**
     * Returns a short detail only when it is safe and readable. This is used as
     * a fallback for app-specific messages that do not have a localized key.
     */
    fun safeDetail(raw: String?): String? {
        val original = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (original.length > MAX_DETAIL_LENGTH || original.contains('\n') || original.contains('\r')) {
            return null
        }
        val normalized = original.replace(whitespacePattern, " ")
            .replace(credentialsPattern, "\$1***:***@")
            .replace(querySecretPattern, "\$1***")
        val lower = normalized.lowercase()
        if (lower.contains("exception") || lower.startsWith("java.") ||
            lower.startsWith("kotlin.") || lower.contains(" at ")) {
            return null
        }
        return normalized.takeIf { it.length <= MAX_DETAIL_LENGTH }
    }

    private fun classify(error: Throwable, inspectCause: Boolean): UserErrorClassification {
        val kind = when (error) {
            is SocketTimeoutException -> UserErrorKind.TIMEOUT
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketException -> UserErrorKind.NETWORK
            is SSLHandshakeException,
            is SSLException -> UserErrorKind.NETWORK
            is FileNotFoundException -> UserErrorKind.NOT_FOUND
            is SecurityException -> UserErrorKind.PERMISSION
            is IOException -> UserErrorKind.NETWORK
            else -> UserErrorKind.UNKNOWN
        }
        if (kind != UserErrorKind.UNKNOWN) return UserErrorClassification(kind)

        val fromMessage = classify(error.message)
        if (fromMessage.kind != UserErrorKind.UNKNOWN) return fromMessage
        if (inspectCause && error.cause != null && error.cause !== error) {
            return classify(error.cause)
        }
        return UserErrorClassification(UserErrorKind.UNKNOWN, safeDetail(error.message))
    }

    private fun classifyStatus(status: Int?): UserErrorKind? = when {
        status == null || status < 0 -> null
        status == 401 -> UserErrorKind.AUTHENTICATION
        status == 403 -> UserErrorKind.PERMISSION
        status == 404 -> UserErrorKind.NOT_FOUND
        status == 408 || status == 504 -> UserErrorKind.TIMEOUT
        status == 400 -> UserErrorKind.REQUEST_REJECTED
        status in 500..599 -> UserErrorKind.SERVER
        status in 400..499 -> UserErrorKind.SERVER
        else -> null
    }

    private const val MAX_DETAIL_LENGTH = 160
}
