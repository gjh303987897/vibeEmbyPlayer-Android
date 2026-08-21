package com.vibeplayer.app.ui.search

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

private const val PAGE_SIZE = 36

data class SearchUiState(
    val serverId: String = "",
    val query: String = "",
    val activeTerm: String = "",
    val results: List<MediaItem> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var loaded = 0
    private var hasMore = false

    /** Monotonic search generation: increments on each new search so stale
     *  responses from an earlier query can be discarded (avoids race overwrites). */
    @Volatile
    private var searchGeneration = 0

    /** Updates the local search draft without triggering a request (IME friendly). */
    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    /** Submits the current query and starts a fresh search. */
    fun submitSearch() {
        val term = _uiState.value.query.trim()
        if (term.isEmpty()) return
        val session = activeSessionManager.activeSession.value ?: return
        val generation = ++searchGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(serverId = session.server.id, activeTerm = term, loading = true, results = emptyList(), error = null) }
            loaded = 0
            hasMore = false
            val client = repository.clientFor(session.server.serviceType)
            val page = client.searchItems(session, term, startIndex = 0, limit = PAGE_SIZE).getOrNull()
            if (generation != searchGeneration) return@launch
            if (page != null) {
                loaded = page.items.size
                hasMore = (page.total ?: loaded) > loaded
                _uiState.update { it.copy(results = page.items, hasMore = hasMore, loading = false) }
            } else {
                _uiState.update { it.copy(loading = false, error = "Search failed") }
            }
        }
    }

    fun loadMore() {
        val term = _uiState.value.activeTerm
        if (term.isEmpty()) return
        val session = activeSessionManager.activeSession.value ?: return
        if (_uiState.value.loadingMore || !hasMore) return
        val generation = searchGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val client = repository.clientFor(session.server.serviceType)
            val page = client.searchItems(session, term, startIndex = loaded, limit = PAGE_SIZE).getOrNull()
            if (generation != searchGeneration) return@launch
            if (page != null) {
                loaded += page.items.size
                hasMore = (page.total ?: loaded) > loaded
                _uiState.update { state ->
                    state.copy(results = state.results + page.items, hasMore = hasMore, loadingMore = false)
                }
            } else {
                _uiState.update { it.copy(loadingMore = false) }
            }
        }
    }
}
