package com.vibeplayer.app.di

import com.vibeplayer.app.data.remote.SelfSignedTls
import java.io.IOException
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import com.vibeplayer.app.BuildConfig

/**
 * Centralises OkHttp client construction so every network consumer shares the
 * same timeout / retry / logging policy. Two cached clients are kept — one with
 * normal TLS and one that trusts self-signed certificates (per-server opt-in via
 * [com.vibeplayer.app.model.ServerConfig.trustSelfSignedCertificate]). This
 * guarantees self-signed trust is never applied globally.
 */
@Singleton
class OkHttpClientFactory @Inject constructor() {

    private val defaultClient by lazy { build(trustSelfSigned = false) }
    private val selfSignedClient by lazy { build(trustSelfSigned = true) }

    /**
     * Returns the client appropriate for the given server's TLS policy.
     * Callers should pass `server.trustSelfSignedCertificate`.
     */
    fun client(trustSelfSigned: Boolean): OkHttpClient =
        if (trustSelfSigned) selfSignedClient else defaultClient

    private fun build(trustSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // Request URLs may contain legacy media-server tokens. Keep HTTP
            // logging disabled in every build until a fully redacting logger exists.
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            })
            .addInterceptor(IdempotentRetryInterceptor())

        if (trustSelfSigned) {
            val tls = SelfSignedTls.configuration
            builder.sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
            // Trust the explicitly opted-in certificate chain but still require
            // its certificate to match the configured server host.
            builder.hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
        }
        return builder.build()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 500L

        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE")

        /**
         * Retries transient network failures for idempotent methods only. POST /
         * body-carrying requests are not retried to avoid double-side effects.
         */
        private class IdempotentRetryInterceptor : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val method = request.method.uppercase()
                var attempt = 0
                while (true) {
                    attempt++
                    try {
                        return chain.proceed(request)
                    } catch (e: IOException) {
                        if (attempt >= MAX_ATTEMPTS || method !in IDEMPOTENT_METHODS) throw e
                        try {
                            Thread.sleep(RETRY_DELAY_MS * attempt)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }
                    }
                }
            }
        }
    }
}
