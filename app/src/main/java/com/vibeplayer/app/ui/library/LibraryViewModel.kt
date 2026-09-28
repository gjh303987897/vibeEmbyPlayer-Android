package com.vibeplayer.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.security.PrivacyManager
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
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
    private val activeSessionManager: ActiveSessionManager,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private var library: MediaLibrary? = null
    private var hasMore = true
    private var loaded = 0

    init {
        viewModelScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                val server = activeSessionManager.activeSession.value?.server
                if (server?.privateMode == true && !unlocked) {
                    loaded = 0
                    library = null
                    _uiState.value = LibraryUiState()
                }
                if (server?.privateMode == true && unlocked) {
                    val currentTitle = _uiState.value.title
                    val libraryId = library?.id
                    if (libraryId != null) load(libraryId)
                }
            }
        }
    }

    fun load(libraryId: String) {
        if (loaded > 0) return
        val session = activeSessionManager.activeSession.value ?: return
        if (session.server.privateMode && !privacyManager.privacyMode.value) {
            _uiState.value = LibraryUiState(error = "Service is locked")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(initialLoading = true, error = null) }
            val client = repository.clientFor(session.server.serviceType)

            val librariesResult = client.fetchLibraries(session)
            val lib = librariesResult.getOrNull()?.firstOrNull { it.id == libraryId }
            if (lib == null) {
                _uiState.update {
                    it.copy(
                        initialLoading = false,
                        error = if (librariesResult.isFailure) {
                            librariesResult.exceptionOrNull()?.message ?: "Failed to load libraries"
                        } else {
                            "Library not found"
                        }
                    )
                }
                return@launch
            }
            library = lib

            val pageResult = client.fetchLibraryItems(session, lib, parentId = "", startIndex = 0, limit = PAGE_SIZE)
            val page = pageResult.getOrNull()
            if (page == null) {
                _uiState.update {
                    it.copy(
                        initialLoading = false,
                        error = pageResult.exceptionOrNull()?.message ?: "Failed to load items"
                    )
                }
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
        if (session.server.privateMode && !privacyManager.privacyMode.value) return
        val lib = library ?: return
        if (_uiState.value.loadingMore || !hasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            val client = repository.clientFor(session.server.serviceType)
            val result = client.fetchLibraryItems(session, lib, parentId = "", startIndex = loaded, limit = PAGE_SIZE)
            result.fold(
                onSuccess = { page ->
                    loaded += page.items.size
                    hasMore = (page.total ?: loaded) > loaded
                    _uiState.update { state ->
                        state.copy(
                            items = state.items + page.items,
                            hasMore = hasMore,
                            loadingMore = false,
                            error = null
                        )
                    }
                },
                onFailure = { error ->
                    // Stop auto-pagination until the user explicitly retries;
                    // otherwise a visible error card can trigger the same
                    // request again on every recomposition.
                    hasMore = false
                    _uiState.update {
                        it.copy(
                            loadingMore = false,
                            hasMore = false,
                            error = error.message ?: "Failed to load more items"
                        )
                    }
                }
            )
        }
    }

    /** Clears stale pagination state and retries the complete library load. */
    fun retry(libraryId: String) {
        loaded = 0
        hasMore = true
        library = null
        _uiState.value = LibraryUiState()
        load(libraryId)
    }
}
