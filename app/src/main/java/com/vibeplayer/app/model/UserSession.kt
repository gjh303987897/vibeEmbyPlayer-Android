package com.vibeplayer.app.model

import java.time.Instant

/**
 * Authenticated user session for a media server.
 * The password is never persisted; the access token is stored securely.
 */
data class UserSession(
    val server: ServerConfig,
    val userId: String,
    val username: String,
    val accessToken: String,
    val createdAt: Instant = Instant.now()
)

/** A service card as shown on the services home screen. */
data class ServiceCard(
    val server: ServerConfig,
    val hasSession: Boolean = false,
    val lastUsedAt: String = ""
)
