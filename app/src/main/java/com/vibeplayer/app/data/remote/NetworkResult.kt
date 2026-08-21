package com.vibeplayer.app.data.remote

import java.io.IOException

/** Kind of network error, mirrored from the Qt reference NetworkErrorKind. */
enum class NetworkErrorKind {
    NETWORK,
    HTTP,
    PARSE,
    INVALID_URL,
    UNKNOWN
}

data class NetworkError(
    val kind: NetworkErrorKind,
    val message: String,
    val statusCode: Int = -1,
    val cause: Throwable? = null
) {
    override fun toString(): String = message
    override fun equals(other: Any?): Boolean = other is NetworkError &&
        other.kind == kind && other.message == message && other.statusCode == statusCode
    override fun hashCode(): Int = 31 * kind.hashCode() + message.hashCode()
}

/** A successful network response carrying the raw body bytes. */
data class NetworkBody(
    val statusCode: Int,
    val bytes: ByteArray
)

/**
 * Result of a network request. Mirrors the Qt std::expected usage; on success it
 * carries the body, on failure a [NetworkError].
 */
sealed class NetworkResult {
    data class Success(val body: ByteArray, val statusCode: Int = 200) : NetworkResult()
    data class Failure(val error: NetworkError) : NetworkResult()

    val isSuccess: Boolean get() = this is Success

    fun getOrNull(): ByteArray? = (this as? Success)?.body
    fun errorOrNull(): NetworkError? = (this as? Failure)?.error
}

fun Throwable.toNetworkError(kind: NetworkErrorKind = NetworkErrorKind.NETWORK): NetworkError =
    NetworkError(kind = kind, message = message ?: "Network error", cause = this)

fun IOException.toHttpError(): NetworkError = toNetworkError(NetworkErrorKind.NETWORK)
