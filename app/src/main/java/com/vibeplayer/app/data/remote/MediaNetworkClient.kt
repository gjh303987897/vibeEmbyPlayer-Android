package com.vibeplayer.app.data.remote

import com.vibeplayer.app.di.OkHttpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * Thin suspend wrapper around OkHttp used by the media server clients.
 * All heavy I/O is dispatched to the IO dispatcher.
 */
@Singleton
class MediaNetworkClient @Inject constructor(
    private val clientFactory: OkHttpClientFactory
) {

    suspend fun get(
        url: String,
        headers: okhttp3.Headers,
        trustSelfSigned: Boolean = false
    ): NetworkResult =
        request("GET", url, headers, null, trustSelfSigned)

    suspend fun postJson(
        url: String,
        headers: okhttp3.Headers,
        json: String,
        trustSelfSigned: Boolean = false
    ): NetworkResult =
        request("POST", url, headers, json, trustSelfSigned)

    private suspend fun request(
        method: String,
        url: String,
        headers: okhttp3.Headers,
        body: String?,
        trustSelfSigned: Boolean
    ): NetworkResult = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .headers(headers)
                .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            clientFactory.client(trustSelfSigned).newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful || bytes == null) {
                    NetworkResult.Failure(
                        NetworkError(
                            kind = NetworkErrorKind.HTTP,
                            message = "HTTP ${response.code}",
                            statusCode = response.code
                        )
                    )
                } else {
                    NetworkResult.Success(bytes, response.code)
                }
            }
        } catch (e: IOException) {
            NetworkResult.Failure(e.toNetworkError())
        }
    }
}
