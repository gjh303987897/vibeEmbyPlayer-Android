package com.vibeplayer.app.ui.local

import android.content.Context
import com.vibeplayer.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.data.repository.LocalMediaRepository
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.domain.browse.sortLocalItems
import com.vibeplayer.app.model.BrowseSortOrder
import com.vibeplayer.app.model.BrowseSource
import com.vibeplayer.app.model.LocalMediaItem
import com.vibeplayer.app.model.PlaybackSource
import com.vibeplayer.app.player.PlaybackQueue
import com.vibeplayer.app.player.PlaybackQueueItem
import com.vibeplayer.app.player.PlaybackQueueScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

data class LocalBrowseUiState(
    val roots: List<LocalMediaRootEntity> = emptyList(),
    val stack: List<Pair<String, String>> = emptyList(),
    val items: List<LocalMediaItem> = emptyList(),
    val sortOrder: BrowseSortOrder = BrowseSortOrder.NAME_ASC,
    val loading: Boolean = false,
    val error: String? = null
) {
    val browsingDirectory: Boolean get() = stack.isNotEmpty()
    val directoryName: String get() = stack.lastOrNull()?.first ?: "Local Playback"
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class LocalBrowseViewModel @Inject constructor(
    private val repository: LocalMediaRepository,
    private val playbackQueue: PlaybackQueue,
    private val settings: SettingsDataStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalBrowseUiState())
    val uiState: StateFlow<LocalBrowseUiState> = combine(_uiState, settings.browseSortOrder(BrowseSource.LOCAL)) { state, order ->
        state to order
    }.mapLatest { (state, order) ->
        if (state.items.isEmpty()) state.copy(sortOrder = order)
        else withContext(Dispatchers.Default) { state.copy(items = sortLocalItems(state.items, order), sortOrder = order) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalBrowseUiState())
    private var browseJob: Job? = null
    private var rootsJob: Job? = null
    private var browseGeneration = 0L

    fun setSortOrder(order: BrowseSortOrder) {
        viewModelScope.launch {
            val error = context.getString(R.string.browse_sort_save_failed)
            try {
                settings.setBrowseSortOrder(BrowseSource.LOCAL, order)
                _uiState.update { if (it.error == error) it.copy(error = null) else it }
            } catch (_: java.io.IOException) { _uiState.update { it.copy(error = error) } }
        }
    }

    fun preparePlayback(item: LocalMediaItem) {
        playbackQueue.stage(PlaybackQueueScope(PlaybackSource.LOCAL), uiState.value.items
            .filter { !it.isDirectory }.map { PlaybackQueueItem(it.uri, it.name) }, item.uri)
    }

    fun load() {
        viewModelScope.launch {
            repository.refreshRootAvailability()
            if (rootsJob?.isActive != true) {
                rootsJob = viewModelScope.launch {
                    repository.observeRoots().collect { roots ->
                        _uiState.update { it.copy(roots = roots) }
                    }
                }
            }
        }
    }

    fun addRoot(uri: String, name: String) {
        viewModelScope.launch { repository.addRoot(uri, name) }
    }

    fun removeRoot(id: String) {
        viewModelScope.launch { repository.removeRoot(id) }
    }

    fun openRoot(root: LocalMediaRootEntity) {
        val stack = listOf(root.name to root.path)
        _uiState.update { it.copy(stack = stack) }
        listChildren(stack)
    }

    fun navigateInto(item: LocalMediaItem) {
        if (!item.isDirectory) return
        val stack = _uiState.value.stack + (item.name to item.uri)
        _uiState.update { it.copy(stack = stack) }
        listChildren(stack)
    }

    fun goUp() {
        val stack = _uiState.value.stack
        if (stack.isEmpty()) return
        val newStack = stack.dropLast(1)
        _uiState.update { it.copy(stack = newStack) }
        if (newStack.isEmpty()) {
            browseJob?.cancel()
            browseGeneration++
            _uiState.update { it.copy(items = emptyList(), loading = false, error = null) }
        } else {
            listChildren(newStack)
        }
    }

    fun backToRoots() {
        browseJob?.cancel()
        browseGeneration++
        _uiState.update { it.copy(stack = emptyList(), items = emptyList()) }
    }

    fun retryCurrent() {
        val stack = _uiState.value.stack
        if (stack.isNotEmpty()) listChildren(stack)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun listChildren(stack: List<Pair<String, String>>) {
        browseJob?.cancel()
        val generation = ++browseGeneration
        val uri = stack.last().second
        browseJob = viewModelScope.launch {
            _uiState.update { it.copy(items = emptyList(), loading = true, error = null) }
            repository.listChildren(uri).fold(
                onSuccess = { items ->
                    if (generation == browseGeneration) _uiState.update { it.copy(items = items, loading = false) }
                },
                onFailure = { e ->
                    if (generation == browseGeneration) _uiState.update {
                        it.copy(loading = false, error = e.message ?: "Failed to read folder")
                    }
                }
            )
        }
    }
}
