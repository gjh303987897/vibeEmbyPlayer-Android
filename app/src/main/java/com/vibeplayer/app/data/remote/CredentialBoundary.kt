package com.vibeplayer.app.data.remote

import java.net.ProtocolException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal data class RequestOrigin(val url: HttpUrl, val allowInsecureHttp: Boolean)

internal fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port
internal fun isLoopbackHost(host: String): Boolean = host == "127.0.0.1" || host == "::1" || host == "localhost"

private fun Request.hasCredentials(): Boolean = headers.names().any {
    it.equals("Authorization", true) || it.equals("Proxy-Authorization", true) ||
        it.equals("X-Emby-Token", true) || it.equals("X-MediaBrowser-Token", true) || it.equals("Cookie", true)
} || url.username.isNotEmpty() || url.password.isNotEmpty() || url.queryParameterNames.any {
    it.equals("api_key", true) || it.equals("X-Emby-Token", true)
}

private fun checkCleartext(request: Request, origin: RequestOrigin) {
    if (request.url.scheme == "http" && !isLoopbackHost(request.url.host) &&
        (request.hasCredentials() || request.body != null) &&
        !(origin.allowInsecureHttp && sameOrigin(request.url, origin.url))) {
        throw ProtocolException("HTTP credentials require explicit approval in this server's settings; use HTTPS")
    }
}

/** Capture the original destination once; redirects cannot grant themselves HTTP consent. */
internal class RequestOriginInterceptor(private val allowInsecureHttp: Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = RequestOrigin(chain.request().url, allowInsecureHttp)
        val request = chain.request().newBuilder().tag(RequestOrigin::class.java, origin).build()
        checkCleartext(request, origin)
        return chain.proceed(request)
    }
}

/** Runs for every redirect/authentication follow-up, before headers or bodies are sent. */
internal class CredentialBoundaryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origin = request.tag(RequestOrigin::class.java) ?: throw ProtocolException("Missing request origin")
        if (origin.url.scheme == "https" && request.url.scheme != "https") {
            throw ProtocolException("HTTPS downgrade blocked")
        }
        if (!sameOrigin(origin.url, request.url) && (request.hasCredentials() || request.body != null)) {
            throw ProtocolException("Cross-origin credential redirect blocked; configure the final server address")
        }
        checkCleartext(request, origin)
        return chain.proceed(request)
    }
}
