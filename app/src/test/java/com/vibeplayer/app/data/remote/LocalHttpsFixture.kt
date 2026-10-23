package com.vibeplayer.app.data.remote

import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import okhttp3.mockwebserver.MockWebServer
import okhttp3.HttpUrl

/** Public synthetic localhost key, used only by JVM tests; never packaged in the app. */
internal fun MockWebServer.enableLocalHttps() {
    // setChunkedBody is an HTTP/1.1 fixture; HTTP/2 does not use chunk framing.
    protocols = listOf(okhttp3.Protocol.HTTP_1_1)
    val password = "fixture-only".toCharArray()
    val keys = KeyStore.getInstance("PKCS12")
    requireNotNull(CredentialBoundaryTest::class.java.getResourceAsStream("/localhost-test.p12")).use { keys.load(it, password) }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
    val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    useHttps(tls.socketFactory, false)
}

internal fun MockWebServer.localHttpsUrl(path: String): HttpUrl = url(path).newBuilder().host("localhost").build()
