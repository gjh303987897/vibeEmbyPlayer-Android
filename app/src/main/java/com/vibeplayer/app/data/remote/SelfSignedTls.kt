package com.vibeplayer.app.data.remote

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Builds an SSL/TLS context that trusts every certificate. This is used ONLY
 * when the user explicitly opts in per-server via
 * [com.vibeplayer.app.model.ServerConfig.trustSelfSignedCertificate] so that
 * self-signed Emby / Jellyfin / WebDAV servers can be reached. It is never
 * applied globally and never used for servers that did not opt in.
 */
internal object SelfSignedTls {

    /** A [X509TrustManager] that accepts every presented certificate. */
    fun trustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** An SSLContext initialised with the trust-all trust manager. */
    fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager()), SecureRandom())
        }
}
