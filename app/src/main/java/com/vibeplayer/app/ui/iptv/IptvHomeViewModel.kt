package com.vibeplayer.app.ui.iptv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.IptvChannelEntity
import com.vibeplayer.app.data.repository.IptvRepository
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IptvUiState(
    val server: ServerConfig? = null,
    val channels: List<IptvChannelEntity> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
    val query: String = "",
    val selectedGroup: String? = null,
    val favoritesOnly: Boolean = false,
    val importing: Boolean = false,
    val error: String? = null,
    val info: String? = null
) {
    val groups: List<String> get() = channels.map { it.groupName }.distinct().sorted()

    val filtered: List<IptvChannelEntity>
        get() = channels.filter { channel ->
            (selectedGroup == null || channel.groupName == selectedGroup) &&
                (!favoritesOnly || channel.id in favoriteIds) &&
                (query.isBlank() || channel.name.contains(query, ignoreCase = true))
        }
}

@HiltViewModel
class IptvHomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MediaServerRepository,
    private val iptvRepository: IptvRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvUiState())
    val uiState: StateFlow<IptvUiState> = _uiState.asStateFlow()

    fun load(serverId: String) {
        viewModelScope.launch {
            val server = repository.getServer(serverId) ?: return@launch
            _uiState.update { it.copy(server = server) }
        }
        viewModelScope.launch {
            iptvRepository.observeChannels(serverId).collect { channels ->
                _uiState.update { it.copy(channels = channels) }
            }
        }
        viewModelScope.launch {
            iptvRepository.observeFavoriteIds(serverId).collect { favorites ->
                _uiState.update { it.copy(favoriteIds = favorites) }
            }
        }
    }

    fun importPlaylist(uriString: String, displayName: String) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true, error = null, info = null) }
            iptvRepository.importFromContentUri(server.id, server.name, displayName, uriString).fold(
                onSuccess = { count ->
                    _uiState.update {
                        it.copy(
                            importing = false,
                            info = context.getString(R.string.iptv_imported_channels, count)
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(importing = false, error = e.message ?: "Playlist import failed")
                    }
                }
            )
        }
    }

    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    fun setGroup(group: String?) = _uiState.update { it.copy(selectedGroup = group) }

    fun setFavoritesOnly(enabled: Boolean) = _uiState.update { it.copy(favoritesOnly = enabled) }

    fun toggleFavorite(channelId: String) {
        val server = _uiState.value.server ?: return
        viewModelScope.launch { iptvRepository.toggleFavorite(server.id, channelId) }
    }

    fun clearMessages() = _uiState.update { it.copy(error = null, info = null) }
}
