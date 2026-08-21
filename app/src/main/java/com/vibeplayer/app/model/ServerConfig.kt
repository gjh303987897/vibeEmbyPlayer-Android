package com.vibeplayer.app.model

/**
 * Server / service account configuration.
 * Mirrors the Qt reference ServerConfig.
 */
data class ServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    val serviceType: ServiceType = ServiceType.EMBY,
    val trustSelfSignedCertificate: Boolean = false,
    val autoLogin: Boolean = true,
    val privateMode: Boolean = false
) {
    /** Normalized base URL without trailing slash. */
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')
}
