package com.vibeplayer.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.repository.ActiveSessionManager
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.model.MediaItem
import com.vibeplayer.app.model.MediaLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val serverId: String = "",
    val serverName: String = "",
    val continueWatching: List<MediaItem> = emptyList(),
    val libraries: List<MediaLibrary> = emptyList(),
    val suggestedSeries: List<MediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MediaServerRepository,
    private val activeSessionManager: ActiveSessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val session = activeSessionManager.activeSession.value ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(loading = true, error = null, serverId = session.server.id, serverName = session.server.name)
            }
            val client = repository.clientFor(session.server.serviceType)
            coroutineScope {
                val continueDeferred = async { client.fetchContinueWatching(session, limit = 20) }
                val librariesDeferred = async { client.fetchLibraries(session) }
                val suggestionsDeferred = async { client.fetchSuggestedSeries(session, limit = 12) }
                var firstError: Throwable? = null
                val continueResult = continueDeferred.await()
                val continueWatching: List<MediaItem> = continueResult.getOrDefault(emptyList())
                if (firstError == null) firstError = continueResult.exceptionOrNull()
                val librariesResult = librariesDeferred.await()
                val libraries: List<MediaLibrary> = librariesResult.getOrDefault(emptyList())
                if (firstError == null) firstError = librariesResult.exceptionOrNull()
                val suggestedResult = suggestionsDeferred.await()
                val suggested: List<MediaItem> = suggestedResult.getOrDefault(emptyList())
                if (firstError == null) firstError = suggestedResult.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        continueWatching = continueWatching,
                        libraries = libraries,
                        suggestedSeries = suggested,
                        loading = false,
                        error = firstError?.message ?: if (
                            firstError != null ||
                            (continueWatching.isEmpty() && libraries.isEmpty() && suggested.isEmpty())
                        ) "Failed to load home" else null
                    )
                }
            }
        }
    }
}
