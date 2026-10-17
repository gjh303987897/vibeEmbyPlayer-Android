package com.vibeplayer.app.ui.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.model.IptvChannel
import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.repository.IptvRepository
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.R
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine

data class IptvUiState(
    val server: ServerConfig? = null,
    val channels: List<IptvChannel> = emptyList(),
    val qualityPreference: IptvQualityPreference = IptvQualityPreference.HIGHEST,
    val favoriteIds: Set<String> = emptySet(),
    val query: String = "",
    val selectedGroup: String? = null,
    val favoritesOnly: Boolean = false,
    val importing: Boolean = false,
    val selectingFile: Boolean = false,
    val error: String? = null,
    val info: String? = null
) {
    val groups: List<String> get() = channels.map { it.groupName }.distinct().sorted()

    val filtered: List<IptvChannel>
        get() = channels.filter { channel ->
            (selectedGroup == null || channel.groupName == selectedGroup) &&
                (!favoritesOnly || channel.isFavourite(favoriteIds)) &&
                (query.isBlank() || channel.name.contains(query, ignoreCase = true))
        }
}

@HiltViewModel
class IptvHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaServerRepository,
    private val iptvRepository: IptvRepository,
    private val privacyManager: PrivacyManager,
    private val settings: SettingsDataStore,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val importRequest = IptvImportRequest(savedStateHandle)
    private val _uiState = MutableStateFlow(IptvUiState(selectingFile = importRequest.pending))
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            settings.playerPreferences.collect { prefs -> _uiState.update { it.copy(qualityPreference = prefs.iptvQuality) } }
        }
    }

    fun load(serverId: String) {
        // Popping the player re-enters this composition. Keep the existing subscription;
        // returning to a page is not another import/load operation.
        if (_uiState.value.server?.id == serverId && loadJob?.isActive == true) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val server = repository.getServer(serverId) ?: return@launch
            if (server.privateMode && !privacyManager.privacyMode.value) {
                importRequest.cancel()
                importJob?.cancel()
                _uiState.value = IptvUiState(qualityPreference = _uiState.value.qualityPreference)
                return@launch
            }
            _uiState.update { it.copy(server = server) }
            launch {
                privacyManager.privacyMode.collect { unlocked ->
                    if (!unlocked && _uiState.value.server?.privateMode == true) {
                        importRequest.cancel()
                        importJob?.cancel()
                        _uiState.value = IptvUiState(qualityPreference = _uiState.value.qualityPreference)
                    }
                }
            }
            launch {
                combine(iptvRepository.observeChannels(serverId), iptvRepository.observeFavoriteIds(serverId)) { channels, favorites ->
                    channels to favorites
                }.collect { (channels, favorites) ->
                    if (_uiState.value.server?.id == serverId &&
                        (!server.privateMode || privacyManager.privacyMode.value))
                        _uiState.update { it.copy(channels = channels, favoriteIds = favorites) }
                }
            }
        }
    }

    fun beginImportSelection(): Boolean {
        val server = _uiState.value.server ?: return false
        if (server.privateMode && !privacyManager.privacyMode.value) return false
        if (!importRequest.begin(server.id, _uiState.value.importing || importJob?.isActive == true)) return false
        _uiState.update { it.copy(selectingFile = true) }
        return true
    }

    fun importSelectionFailed() {
        importRequest.cancel()
        _uiState.update { it.copy(selectingFile = false, error = context.getString(R.string.message_error_generic)) }
    }

    fun onImportSelectionResult(uriString: String?, displayName: String) {
        val selected = importRequest.complete(uriString)
        _uiState.update { it.copy(selectingFile = importRequest.pending) }
        // Back in the system picker is cancellation, not a request to import the previous URI.
        if (selected == null || _uiState.value.importing || importJob?.isActive == true) return
        // Set synchronously, before launch; multiple callbacks in one frame must not queue jobs.
        _uiState.update { it.copy(importing = true, error = null, info = null) }
        importJob = viewModelScope.launch {
            try {
                val server = repository.getServer(selected.serviceId) ?: return@launch
                if (server.privateMode && !privacyManager.privacyMode.value) return@launch
                iptvRepository.importFromContentUri(server.id, server.name, displayName, selected.uri).fold(
                    onSuccess = { count ->
                        _uiState.update { it.copy(info = context.getString(R.string.iptv_imported_channels, count)) }
                    },
                    onFailure = { e ->
                        _uiState.update { it.copy(error = e.message ?: "Playlist import failed") }
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(error = context.getString(R.string.message_error_generic)) }
            } finally {
                _uiState.update { it.copy(importing = false) }
            }
        }
    }

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    fun setGroup(group: String?) = _uiState.update { it.copy(selectedGroup = group) }

    fun setFavoritesOnly(enabled: Boolean) = _uiState.update { it.copy(favoritesOnly = enabled) }

    fun toggleFavorite(channel: IptvChannel) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch { iptvRepository.toggleFavorite(server.id, channel.sourceIds) }
    }

    fun setQualityPreference(preference: IptvQualityPreference) {
        viewModelScope.launch {
            try { settings.setIptvQuality(preference) }
            catch (_: java.io.IOException) { _uiState.update { it.copy(error = context.getString(R.string.player_settings_save_failed)) } }
        }
    }

    fun clearMessages() = _uiState.update { it.copy(error = null, info = null) }
}
