package com.vibeplayer.app.ui.link

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.LinkPlaybackHistoryEntity
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.domain.link.LinkPlaybackService
import com.vibeplayer.app.model.ServerConfig
import com.vibeplayer.app.util.normalizeUrlInput
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
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
    private val historyRepository: PlaybackHistoryRepository,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LinkHomeUiState())
    val uiState: StateFlow<LinkHomeUiState> = _uiState.asStateFlow()
    private var servicesJob: kotlinx.coroutines.Job? = null
    private var historyJob: kotlinx.coroutines.Job? = null

    fun load(serverId: String) {
        servicesJob?.cancel()
        servicesJob = viewModelScope.launch {
            combine(repository.observeServices(), privacyManager.privacyMode) { services, unlocked ->
                services.firstOrNull { it.id == serverId && (!it.privateMode || unlocked) }
            }.collect { server ->
                if (server == null) _uiState.value = LinkHomeUiState()
                else _uiState.update { it.copy(server = server) }
            }
        }
    }

    fun onUrlInputChange(value: String) {
        _uiState.update {
            it.copy(urlInput = normalizeUrlInput(value), validationError = null)
        }
    }

    /** Validates the typed URL and, on success, prepares navigation to the player. */
    fun playInput() {
        val server = _uiState.value.server ?: return
        if (server.privateMode && !privacyManager.privacyMode.value) return
        val input = _uiState.value.urlInput
        when (val result = LinkPlaybackService.resolvePlaybackUrl(input)) {
            is LinkPlaybackService.Result.Success -> _uiState.update { it.copy(pendingUrl = result.playbackUrl, validationError = null) }
            is LinkPlaybackService.Result.Error -> _uiState.update { it.copy(validationError = result.message) }
        }
    }

    /** Re-validates a stored URL before replay. */
    fun replay(url: String) {
        val server = _uiState.value.server ?: return
        if (server.privateMode && !privacyManager.privacyMode.value) return
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeHistory() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            combine(privacyManager.privacyMode, uiState.map { it.server }.distinctUntilChanged()) { unlocked, server ->
                unlocked to server
            }.flatMapLatest { (showPrivate, server) ->
                if (server == null || (server.privateMode && !showPrivate)) flowOf(emptyList())
                else historyRepository.observeLinkHistory(showPrivate, server.id)
            }.collect { rows ->
                _uiState.update { it.copy(history = rows) }
            }
        }
    }
}
