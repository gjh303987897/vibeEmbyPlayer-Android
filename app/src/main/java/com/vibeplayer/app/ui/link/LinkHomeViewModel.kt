package com.vibeplayer.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.domain.link.LinkPlaybackService
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.util.normalizeUrlInput
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LinkHomeUiState(
    val server: ServerConfig? = null,
    val urlInput: String = "",
    val validationError: String? = null,
    val history: List<LinkPlaybackHistoryEntity> = emptyList(),
    val pendingUrl: String? = null
) {
    val groupedByDate: List<Pair<String, List<LinkPlaybackHistoryEntity>>>
        get() = history
            .map { it.playedDate to it }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareByDescending { it })
            .toList()
}

@HiltViewModel
class LinkHomeViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val historyRepository: PlaybackHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkHomeUiState())
    val uiState: StateFlow<LinkHomeUiState> = _uiState.asStateFlow()

    fun load(serverId: String) {
        viewModelScope.launch {
            val server = repository.getServer(serverId) ?: return@launch
            _uiState.update { it.copy(server = server) }
        }
    }

    fun onUrlInputChange(value: String) {
        _uiState.update {
            it.copy(urlInput = normalizeUrlInput(value), validationError = null)
        }
    }

    /** Validates the typed URL and, on success, prepares navigation to the player. */
    fun playInput() {
        val input = _uiState.value.urlInput
        when (val result = LinkPlaybackService.resolvePlaybackUrl(input)) {
            is LinkPlaybackService.Result.Success -> _uiState.update { it.copy(pendingUrl = result.playbackUrl, validationError = null) }
            is LinkPlaybackService.Result.Error -> _uiState.update { it.copy(validationError = result.message) }
        }
    }

    /** Re-validates a stored URL before replay. */
    fun replay(url: String) {
        when (val result = LinkPlaybackService.resolvePlaybackUrl(url)) {
            is LinkPlaybackService.Result.Success -> _uiState.update { it.copy(pendingUrl = result.playbackUrl, validationError = null) }
            is LinkPlaybackService.Result.Error -> _uiState.update { it.copy(validationError = result.message) }
        }
    }

    fun consumePendingUrl() {
        _uiState.update { it.copy(pendingUrl = null) }
    }

    fun delete(entry: LinkPlaybackHistoryEntity) {
        viewModelScope.launch {
            historyRepository.deleteHistory(entry.id)
        }
    }

    fun observeHistory() {
        viewModelScope.launch {
            historyRepository.observeLinkHistory().collect { rows ->
                _uiState.update { it.copy(history = rows) }
            }
        }
    }
}
