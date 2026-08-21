package com.vibeplayer.app.data.repository

import com.vibeplayer.app.data.local.datastore.SecureSessionStore
import com.vibeplayer.app.data.local.datastore.ServiceStore
import com.vibeplayer.app.data.remote.EmbyClient
import com.vibeplayer.app.data.remote.JellyfinClient
import com.vibeplayer.app.data.remote.MediaServerClient
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import com.vibeplayer.app.model.UserSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Single data entry point for media server accounts (Emby / Jellyfin / etc.).
 * Selects the correct API client by service type and delegates persistence of
 * service accounts to [ServiceStore] and of session tokens to
 * [SecureSessionStore]. ViewModels must go through this layer.
 */
@Singleton
class MediaServerRepository @Inject constructor(
    private val embyClient: EmbyClient,
    private val jellyfinClient: JellyfinClient,
    private val serviceStore: ServiceStore,
    private val secureSessionStore: SecureSessionStore
) {

    /** Observable list of configured service accounts. */
    fun observeServices(): Flow<List<ServerConfig>> = serviceStore.services

    suspend fun getServices(): List<ServerConfig> = serviceStore.services.first()

    suspend fun getServer(serverId: String): ServerConfig? =
        serviceStore.services.first().firstOrNull { it.id == serverId }

    suspend fun addServer(config: ServerConfig) = serviceStore.upsert(config)

    suspend fun updateServer(config: ServerConfig) = serviceStore.upsert(config)

    suspend fun removeServer(serverId: String) = serviceStore.remove(serverId)

    /** Persists a manually reordered service list (drag-sort on the Services page). */
    suspend fun reorderServices(ordered: List<ServerConfig>) = serviceStore.saveAll(ordered)

    fun clientFor(type: ServiceType): MediaServerClient = when (type) {
        ServiceType.EMBY -> embyClient
        ServiceType.JELLYFIN -> jellyfinClient
        else -> throw UnsupportedOperationException("Service type not yet supported: $type")
    }

    suspend fun login(server: ServerConfig, password: String): Result<UserSession> {
        val result = clientFor(server.serviceType).login(server, server.username, password)
        if (result.isSuccess) {
            result.getOrNull()?.let { session ->
                secureSessionStore.saveSession(server.id, session.userId, session.accessToken)
            }
        }
        return result
    }

    /** Builds a [UserSession] from a previously stored token, or null if none. */
    fun restoreSession(server: ServerConfig): UserSession? {
        val token = secureSessionStore.accessToken(server.id) ?: return null
        val userId = secureSessionStore.userId(server.id) ?: return null
        return UserSession(
            server = server,
            userId = userId,
            username = server.username,
            accessToken = token
        )
    }

    fun hasSession(server: ServerConfig): Boolean = secureSessionStore.hasSession(server.id)

    fun logout(server: ServerConfig) {
        secureSessionStore.clearSession(server.id)
    }
}
