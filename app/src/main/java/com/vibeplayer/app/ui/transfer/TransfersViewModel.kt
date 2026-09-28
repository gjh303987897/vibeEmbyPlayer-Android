package com.vibeplayer.app.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.TransferStatus
import com.vibeplayer.app.data.local.db.entity.TransferTaskEntity
import com.vibeplayer.app.data.repository.TransferRepository
import com.vibeplayer.app.data.repository.MediaServerRepository
import com.vibeplayer.app.security.PrivacyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine

data class TransfersUiState(
    val tasks: List<TransferTaskEntity> = emptyList()
)

@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val transferRepository: TransferRepository,
    private val mediaServerRepository: MediaServerRepository,
    private val privacyManager: PrivacyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransfersUiState())
    val uiState: StateFlow<TransfersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                transferRepository.observeTasks(),
                mediaServerRepository.observeServices(),
                privacyManager.privacyMode
            ) { tasks, servers, includePrivate ->
                val visibleIds = servers
                    .filter { includePrivate || !it.privateMode }
                    .mapTo(mutableSetOf()) { it.id }
                tasks.filter { it.serverId in visibleIds }
            }.collect { tasks ->
                _uiState.update { it.copy(tasks = tasks) }
            }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch { transferRepository.cancel(id) }
    }

    fun retry(id: String) {
        viewModelScope.launch { transferRepository.retry(id) }
    }

    fun pause(id: String) {
        viewModelScope.launch { transferRepository.pause(id) }
    }

    fun resume(id: String) {
        viewModelScope.launch { transferRepository.resume(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch { transferRepository.remove(id) }
    }

    fun clearFinished() {
        viewModelScope.launch { transferRepository.clearFinished() }
    }

    fun statusLabel(status: TransferStatus): String = when (status) {
        TransferStatus.QUEUED -> "Queued"
        TransferStatus.RUNNING -> "Running"
        TransferStatus.PAUSED -> "Paused"
        TransferStatus.DONE -> "Done"
        TransferStatus.FAILED -> "Failed"
        TransferStatus.CANCELED -> "Canceled"
    }
}
