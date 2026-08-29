package com.vibeplayer.app.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailsUiState(
    val item: MediaItem? = null,
    val seasons: List<MediaItem> = emptyList(),
    val selectedSeasonId: String = "",
    val episodes: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val episodesLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MediaDetailsViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = _uiState.asStateFlow()

    fun load(itemId: String) {
        val session = activeSessionManager.activeSession.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            val client = repository.clientFor(session.server.serviceType)
            val itemResult = client.fetchItemDetails(session, itemId)
            val item = itemResult.getOrNull()
            if (item == null) {
                _uiState.update {
                    it.copy(loading = false, error = itemResult.exceptionOrNull()?.message ?: "Item not found")
                }
                return@launch
            }
            _uiState.update { it.copy(item = item, loading = false) }

            val isSeries = item.itemType.equals("Series", ignoreCase = true)
            val isEpisode = item.itemType.equals("Episode", ignoreCase = true)
            if (isSeries || isEpisode) {
                loadSeasons(session.server.serviceType, client, item)
            }
        }
    }

    private suspend fun loadSeasons(
        serviceType: com.vibeplayer.app.model.ServiceType,
        client: com.vibeplayer.app.data.remote.MediaServerClient,
        item: MediaItem
    ) {
        val session = activeSessionManager.activeSession.value ?: return
        val seriesId = item.seriesId.ifEmpty { item.id }
        val seasonsResult = client.fetchSeriesSeasons(session, seriesId)
        val seasons = seasonsResult.getOrNull() ?: emptyList()
        if (seasonsResult.isFailure) {
            _uiState.update {
                it.copy(error = seasonsResult.exceptionOrNull()?.message ?: "Failed to load seasons")
            }
            return
        }

        // For an episode, prefer the season that owns it (via ParentId).
        val initialSeason = seasons.firstOrNull { it.id == item.parentId } ?: seasons.firstOrNull()
        val selectedId = initialSeason?.id ?: ""
        _uiState.update {
            it.copy(seasons = seasons, selectedSeasonId = selectedId)
        }
        if (selectedId.isNotEmpty()) {
            loadEpisodes(session, client, seriesId, selectedId)
        }
    }

    fun selectSeason(seasonId: String) {
        val session = activeSessionManager.activeSession.value ?: return
        val item = _uiState.value.item ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(selectedSeasonId = seasonId) }
            val client = repository.clientFor(session.server.serviceType)
            val seriesId = item.seriesId.ifEmpty { item.id }
            loadEpisodes(session, client, seriesId, seasonId)
        }
    }

    private suspend fun loadEpisodes(
        session: com.vibeplayer.app.model.UserSession,
        client: com.vibeplayer.app.data.remote.MediaServerClient,
        seriesId: String,
        seasonId: String
    ) {
        _uiState.update { it.copy(episodesLoading = true) }
        val result = client.fetchSeasonEpisodes(session, seriesId, seasonId)
        val episodes = result.getOrNull() ?: emptyList()
        _uiState.update {
            it.copy(
                episodes = episodes,
                episodesLoading = false,
                error = result.exceptionOrNull()?.message ?: if (result.isFailure) "Failed to load episodes" else null
            )
        }
    }

    fun retry(itemId: String) = load(itemId)
}
