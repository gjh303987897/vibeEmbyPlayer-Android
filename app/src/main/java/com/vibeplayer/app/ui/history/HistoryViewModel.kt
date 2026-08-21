package com.vibeplayer.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibeplayer.app.data.local.db.entity.DailyUsageStatEntity
import com.vibeplayer.app.data.repository.PlaybackHistoryRepository
import com.vibeplayer.app.model.PlaybackHistoryEntry
import com.vibeplayer.app.model.PlaybackSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val entries: List<PlaybackHistoryEntry> = emptyList(),
    val usage: List<DailyUsageStatEntity> = emptyList(),
    val sourceFilter: PlaybackSource? = null,
    val page: Int = 0,
    val pageSize: Int = PlaybackHistoryRepository.DEFAULT_PAGE_SIZE,
    val total: Int = 0
) {
    val totalPages: Int
        get() = if (pageSize <= 0) 0 else (total + pageSize - 1) / pageSize

    val hasPrevious: Boolean get() = page > 0
    val hasNext: Boolean get() = page + 1 < totalPages

    val groupedByDate: List<Pair<String, List<PlaybackHistoryEntry>>>
        get() = entries
            .map { it.playedDate to it }
            .groupBy({ it.first }, { it.second })
            .toSortedMap(compareByDescending { it })
            .toList()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: PlaybackHistoryRepository
) : ViewModel() {

    private val filter = MutableStateFlow<PlaybackSource?>(null)
    private val page = MutableStateFlow(0)

    private val pageEntries: kotlinx.coroutines.flow.Flow<List<PlaybackHistoryEntry>> =
        combine(filter, page) { f, p -> f to p }
            .flatMapLatest { (f, p) ->
                historyRepository.observeHistory(f, historyRepository.PAGE_SIZE, p * historyRepository.PAGE_SIZE)
            }

    private val totalCount: kotlinx.coroutines.flow.Flow<Int> =
        filter
            .flatMapLatest { historyRepository.observeCount(it) }
            .distinctUntilChanged()

    val uiState: StateFlow<HistoryUiState> = combine(
        pageEntries,
        totalCount,
        filter,
        page,
        historyRepository.observeUsage()
    ) { entries, total, selected, currentPage, usage ->
        HistoryUiState(
            entries = entries,
            usage = usage,
            sourceFilter = selected,
            page = currentPage,
            total = total
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setSourceFilter(source: PlaybackSource?) {
        filter.value = source
        page.value = 0
    }

    fun nextPage() {
        val state = uiState.value
        if (state.hasNext) page.value = state.page + 1
    }

    fun previousPage() {
        val state = uiState.value
        if (state.hasPrevious) page.value = state.page - 1
    }

    fun deleteHistory(entry: PlaybackHistoryEntry) {
        viewModelScope.launch {
            historyRepository.deleteHistory(entry.id)
        }
    }

    companion object {
        val SOURCE_FILTERS = listOf(
            null,
            PlaybackSource.EMBY,
            PlaybackSource.JELLYFIN,
            PlaybackSource.WEBDAV,
            PlaybackSource.IPTV,
            PlaybackSource.LOCAL,
            PlaybackSource.LINK
        )
    }
}
