package com.vibeplayer.app.di

import com.vibeplayer.app.data.remote.SelfSignedTls
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.vibeplayer.app.data.remote.RequestOriginInterceptor
import com.vibeplayer.app.data.remote.CredentialBoundaryInterceptor

/**
 * Centralises OkHttp client construction so every network consumer shares the
 * same timeout / retry / logging policy. Cached clients isolate normal TLS and
 * self-signed certificate trust, with separate HTTP consent (per-server opt-in via
 * [com.vibeplayer.app.model.ServerConfig.trustSelfSignedCertificate]). This
 * guarantees self-signed trust is never applied globally.
 */
@Singleton
class OkHttpClientFactory @Inject constructor() {

    private val defaultClient by lazy { build(trustSelfSigned = false) }
    private val selfSignedClient by lazy { build(trustSelfSigned = true) }
    private val insecureDefaultClient by lazy { build(false, true) }
    private val insecureSelfSignedClient by lazy { build(true, true) }

    /**
     * Returns the client appropriate for the given server's TLS policy.
     * Callers should pass `server.trustSelfSignedCertificate`.
     */
    fun client(trustSelfSigned: Boolean, allowInsecureHttp: Boolean = false): OkHttpClient = when {
        allowInsecureHttp && trustSelfSigned -> insecureSelfSignedClient
        allowInsecureHttp -> insecureDefaultClient
        trustSelfSigned -> selfSignedClient
        else -> defaultClient
    }

    private fun build(trustSelfSigned: Boolean, allowInsecureHttp: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(RequestOriginInterceptor(allowInsecureHttp))
            .addNetworkInterceptor(CredentialBoundaryInterceptor())
            // Request URLs may contain legacy media-server tokens. Keep HTTP
            // logging disabled in every build until a fully redacting logger exists.
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            })

        if (trustSelfSigned) {
            val tls = SelfSignedTls.configuration
            builder.sslSocketFactory(tls.sslContext.socketFactory, tls.trustManager)
            // Keep OkHttp's default hostname verifier in both modes. Certificate
            // trust opt-in must never disable certificate-to-host matching.
        }
        return builder.build()
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
