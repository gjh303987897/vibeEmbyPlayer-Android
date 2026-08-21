package com.vibeplayer.app.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vibeplayer.app.data.local.db.dao.IptvChannelDao
import com.vibeplayer.app.data.local.db.dao.IptvPlaylistDao
import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.local.db.entity.IptvPlaylistEntity
import com.vibeplayer.app.data.remote.iptv.IptvParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.iptvPrefsDataStore by preferencesDataStore(name = "iptv_prefs")

/**
 * IPTV playlist & channel repository. A selected .m3u/.m3u8 file is copied into
 * the app-private data directory (the managed copy) and its channels are parsed
 * and persisted per service, mirroring the Qt IptvPlaylistStore lifecycle.
 * Favorites are kept as a small preference-backed id set.
 */
@Singleton
class IptvRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: IptvPlaylistDao,
    private val channelDao: IptvChannelDao
) {

    private object Keys {
        fun favorites(serviceId: String) = stringPreferencesKey("favorite_channel_ids_$serviceId")
    }

    /**
     * Observes the favorite channel id set for a single service. Favorites are
     * scoped per service so the same channel name in different playlists does
     * not share/collide its favorite state.
     */
    fun observeFavoriteIds(serviceId: String): Flow<Set<String>> =
        context.iptvPrefsDataStore.data.map { prefs ->
            prefs[Keys.favorites(serviceId)]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
        }

    suspend fun toggleFavorite(serviceId: String, channelId: String) {
        context.iptvPrefsDataStore.edit { prefs ->
            val current = prefs[Keys.favorites(serviceId)]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val updated = if (channelId in current) current - channelId else current + channelId
            prefs[Keys.favorites(serviceId)] = updated.joinToString(",")
        }
    }

    /**
     * Copies the selected playlist into app-private storage and persists its
     * channels for the given service. Returns the number of imported channels.
     */
    suspend fun importPlaylist(
        serviceId: String,
        serviceName: String,
        fileName: String,
        bytes: ByteArray
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "iptv").apply { mkdirs() }
            val safeName = sanitizeFileName(fileName)
            val managedFile = File(dir, safeName)
            managedFile.writeBytes(bytes)

            val playlist = IptvPlaylistEntity(
                id = serviceId,
                serviceId = serviceId,
                name = serviceName,
                sourceType = "LocalFile",
                sourcePath = managedFile.absolutePath,
                importedPath = managedFile.absolutePath,
                importedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
            playlistDao.upsert(playlist)
            channelDao.deleteByPlaylist(serviceId)

            val text = IptvParser.decodeText(bytes)
            val fallbackName = fileName.substringBeforeLast('.').ifEmpty { "IPTV Channel" }
            val channels = if (IptvParser.looksLikeHlsManifest(text)) {
                listOf(
                    IptvChannelEntity(
                        id = IptvParser.stableId(managedFile.absolutePath),
                        playlistId = serviceId,
                        name = fallbackName,
                        groupName = IptvParser.DEFAULT_GROUP,
                        logoUrl = "",
                        streamUrl = Uri.fromFile(managedFile).toString(),
                        sortOrder = 0
                    )
                )
            } else {
                IptvParser.parseChannels(text, fallbackName).map {
                    IptvChannelEntity(
                        id = it.id,
                        playlistId = serviceId,
                        name = it.name,
                        groupName = it.groupName,
                        logoUrl = it.logoUrl,
                        streamUrl = it.streamUrl,
                        sortOrder = it.sortOrder
                    )
                }
            }
            channelDao.upsertAll(channels)
            channels.size
        }
    }

    /**
     * Reads a playlist picked via the Storage Access Framework and imports it.
     */
    suspend fun importFromContentUri(
        serviceId: String,
        serviceName: String,
        displayName: String,
        uriString: String
    ): Result<Int> {
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read selected file")
            }
        }.getOrElse { return Result.failure(it) }
        return importPlaylist(serviceId, serviceName, displayName, bytes)
    }

    fun observeChannels(serviceId: String): Flow<List<IptvChannelEntity>> =
        channelDao.observeByPlaylist(serviceId)

    suspend fun hasPlaylist(serviceId: String): Boolean =
        playlistDao.findByServiceId(serviceId) != null

    /**
     * Deletes all locally managed data for an IPTV service: its channels, the
     * playlist row and the managed copy file. Callers should invoke this when a
     * service is removed so no orphaned data remains.
     */
    suspend fun deleteServiceData(serviceId: String) {
        channelDao.deleteByPlaylist(serviceId)
        playlistDao.findByServiceId(serviceId)?.let {
            runCatching { File(it.importedPath).delete() }
        }
        playlistDao.deleteByServiceId(serviceId)
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "playlist.m3u" }
    }
}
