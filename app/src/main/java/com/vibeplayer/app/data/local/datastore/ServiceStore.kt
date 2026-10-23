package com.vibeplayer.app.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.model.ServiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the list of configured media service accounts (Emby / Jellyfin /
 * WebDAV / IPTV / Link). Only non-sensitive configuration is stored here;
 * credentials and access tokens are stored separately via secure storage.
 */
@Singleton
class ServiceStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json
) {
    // Single DataStore instance (created once per app). A corruption handler
    // resets a corrupt on-disk file to empty instead of throwing and crashing
    // the app on every launch.
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(context.applicationContext.filesDir, "datastore/services.preferences_pb") }
    )

    private object Keys {
        val SERVICES = stringPreferencesKey("services_json")
    }

    @Serializable
    private data class ServerConfigDto(
        val id: String,
        val name: String,
        val baseUrl: String,
        val username: String,
        val serviceType: String,
        // Default preserves compatibility with service JSON written before this
        // option existed; upgrading must never silently weaken TLS verification.
        val trustSelfSignedCertificate: Boolean = false,
        val autoLogin: Boolean,
        val privateMode: Boolean,
        val allowInsecureHttp: Boolean = false
    )

    val services: Flow<List<ServerConfig>> = dataStore.data
        .map { prefs ->
            prefs[Keys.SERVICES]?.let { decode(it) } ?: emptyList()
        }

    private suspend fun updateServices(transform: (List<ServerConfig>) -> List<ServerConfig>) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.SERVICES]?.let(::decode).orEmpty()
            val dto = transform(current).map { it.toDto() }
            prefs[Keys.SERVICES] = json.encodeToString(ListSerializer(ServerConfigDto.serializer()), dto)
        }
    }

    suspend fun upsert(config: ServerConfig) {
        updateServices { current ->
            current.map { if (it.id == config.id) config else it }
                .let { list -> if (list.none { it.id == config.id }) list + config else list }
        }
    }

    suspend fun remove(serverId: String) {
        updateServices { current -> current.filter { it.id != serverId } }
    }

    suspend fun reorder(orderedIds: List<String>) = updateServices { reorderExistingServices(it, orderedIds) }

    private fun decode(raw: String): List<ServerConfig> = try {
        json.decodeFromString(ListSerializer(ServerConfigDto.serializer()), raw)
            .map { it.toModel() }
    } catch (e: SerializationException) {
        emptyList()
    }

    private fun ServerConfig.toDto() = ServerConfigDto(
        id = id,
        name = name,
        baseUrl = baseUrl,
        username = username,
        serviceType = serviceType.displayName,
        trustSelfSignedCertificate = trustSelfSignedCertificate,
        autoLogin = autoLogin,
        privateMode = privateMode,
        allowInsecureHttp = allowInsecureHttp
    )

    private fun ServerConfigDto.toModel() = ServerConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        username = username,
        serviceType = ServiceType.fromDisplayName(serviceType),
        trustSelfSignedCertificate = trustSelfSignedCertificate,
        autoLogin = autoLogin,
        privateMode = privateMode,
        allowInsecureHttp = allowInsecureHttp
    )
}
