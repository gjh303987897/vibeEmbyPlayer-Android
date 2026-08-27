package com.vibeplayer.app.di

import com.vibeplayer.app.data.remote.SelfSignedTls
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor

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
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .addInterceptor(IdempotentRetryInterceptor())

        if (trustSelfSigned) {
            val tls = SelfSignedTls.configuration
            builder.sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
            // Opt-in semantics match the desktop client: certificate-chain and
            // host-name errors are accepted for this server only. The UI warns
            // that this permits interception and leaves the option off by default.
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val MAX_RETRIES = 2
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
                        if (attempt >= MAX_RETRIES || method !in IDEMPOTENT_METHODS) throw e
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
