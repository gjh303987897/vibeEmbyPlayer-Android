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

    data class Configuration(
        val sslContext: SSLContext,
        val trustManager: X509TrustManager
    )

    /** One shared manager/context pair, as required by OkHttp's TLS configuration API. */
    val configuration: Configuration by lazy {
        val manager = trustManager()
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(manager), SecureRandom())
        }
        Configuration(context, manager)
    }

    /** A [X509TrustManager] that accepts every presented certificate. */
    private fun trustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
