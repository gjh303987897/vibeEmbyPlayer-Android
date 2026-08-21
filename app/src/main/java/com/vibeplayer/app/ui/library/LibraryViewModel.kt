package com.vibeplayer.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 48

data class LibraryUiState(
    val title: String = "",
    val items: List<MediaItem> = emptyList(),
    val hasMore: Boolean = false,
    val initialLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var library: MediaLibrary? = null
    private var hasMore = true
    private var loaded = 0

    fun load(libraryId: String) {
        if (loaded > 0) return
        val session = activeSessionManager.activeSession.value ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(initialLoading = true, error = null) }
            val client = repository.clientFor(session.server.serviceType)

            val lib = client.fetchLibraries(session).getOrNull()
                ?.firstOrNull { it.id == libraryId }
            if (lib == null) {
                _uiState.update { it.copy(initialLoading = false, error = "Library not found") }
                return@launch
            }
            library = lib

            val page = client.fetchLibraryItems(session, lib, parentId = "", startIndex = 0, limit = PAGE_SIZE)
                .getOrNull()
            if (page == null) {
                _uiState.update { it.copy(initialLoading = false, error = "Failed to load items") }
                return@launch
            }
            loaded = page.items.size
            hasMore = (page.total ?: loaded) > loaded
            _uiState.update {
                it.copy(
                    title = lib.name,
                    items = page.items,
                    hasMore = hasMore,
                    initialLoading = false
                )
            }
        }
    }

    fun loadMore() {
        val session = activeSessionManager.activeSession.value ?: return
        val lib = library ?: return
        if (_uiState.value.loadingMore || !hasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val client = repository.clientFor(session.server.serviceType)
            val page = client.fetchLibraryItems(session, lib, parentId = "", startIndex = loaded, limit = PAGE_SIZE)
                .getOrNull()
            if (page != null) {
                loaded += page.items.size
                hasMore = (page.total ?: loaded) > loaded
                _uiState.update { state ->
                    state.copy(
                        items = state.items + page.items,
                        hasMore = hasMore,
                        loadingMore = false
                    )
                }
            } else {
                _uiState.update { it.copy(loadingMore = false) }
            }
        }
    }
}
