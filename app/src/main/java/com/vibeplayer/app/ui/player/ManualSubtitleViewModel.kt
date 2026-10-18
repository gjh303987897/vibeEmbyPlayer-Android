package com.vibeplayer.app.ui.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.LocalSubtitleLoader
import com.vibeplayer.app.domain.subtitle.*
import com.vibeplayer.app.player.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ManualSubtitleImportState(val loading: Boolean = false, val error: SubtitleImportError? = null)

@HiltViewModel
class ManualSubtitleViewModel @Inject constructor(
    private val manager: PlayerManager,
    private val loader: LocalSubtitleLoader
) : ViewModel() {
    private val _state = MutableStateFlow(ManualSubtitleImportState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun captureSession(): Long = manager.subtitleSessionId

    fun load(uri: Uri, session: Long) {
        job?.cancel()
        _state.value = ManualSubtitleImportState(loading = true)
        job = viewModelScope.launch {
            try {
                manager.ensureSubtitleSession(session)
                val document = loader.load(uri)
                manager.addManualSubtitle(session, document)
                _state.value = ManualSubtitleImportState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ManualSubtitles", "Import failed (${error.javaClass.simpleName})")
                _state.value = ManualSubtitleImportState(error =
                    (error as? SubtitleImportException)?.reason ?: SubtitleImportError.UNREADABLE)
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _state.value = ManualSubtitleImportState() }
}
