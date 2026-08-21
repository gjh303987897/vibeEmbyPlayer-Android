package com.vibeplayer.app.ui.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.LocalMediaRootEntity
import com.vibeplayer.app.data.repository.LocalMediaRepository
import com.vibeplayer.app.model.LocalMediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalBrowseUiState(
    val roots: List<LocalMediaRootEntity> = emptyList(),
    val stack: List<Pair<String, String>> = emptyList(),
    val items: List<LocalMediaItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
) {
    val browsingDirectory: Boolean get() = stack.isNotEmpty()
    val directoryName: String get() = stack.lastOrNull()?.first ?: "Local Playback"
}

@HiltViewModel
class LocalBrowseViewModel @Inject constructor(
    private val repository: LocalMediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LocalBrowseUiState())
    val uiState: StateFlow<LocalBrowseUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            repository.refreshRootAvailability()
            repository.observeRoots().collect { roots ->
                _uiState.update { it.copy(roots = roots) }
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
            _uiState.update { it.copy(items = emptyList(), loading = false, error = null) }
        } else {
            listChildren(newStack)
        }
    }

    fun backToRoots() {
        _uiState.update { it.copy(stack = emptyList(), items = emptyList()) }
    }

    private fun listChildren(stack: List<Pair<String, String>>) {
        val uri = stack.last().second
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            repository.listChildren(uri).fold(
                onSuccess = { items -> _uiState.update { it.copy(items = items, loading = false) } },
                onFailure = { e -> _uiState.update { it.copy(loading = false, error = e.message) } }
            )
        }
    }
}
