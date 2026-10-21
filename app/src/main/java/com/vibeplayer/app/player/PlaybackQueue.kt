package com.vibeplayer.app.player

import com.vibeplayer.app.model.PlaybackSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Identifiers only: stream addresses and credentials are resolved by the source ViewModel. */
data class PlaybackQueueItem(val id: String, val title: String, val subtitle: String = "")
data class PlaybackQueueScope(val source: PlaybackSource, val serviceId: String? = null)
data class PlaybackQueueState(val items: List<PlaybackQueueItem> = emptyList(), val index: Int = -1,
    val autoAdvanceAllowed: Boolean = true) {
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index >= 0 && index < items.lastIndex
}

/** Main-thread, session-only queue. Source owners resolve a selection in the existing player. */
@Singleton
class PlaybackQueue @Inject constructor() {
    private val _state = MutableStateFlow(PlaybackQueueState())
    val state = _state.asStateFlow()
    private var owner: Any? = null
    private var selectItem: ((PlaybackQueueItem) -> Unit)? = null
    private var completeItem: (() -> Unit)? = null
    private var closeSession: (() -> Unit)? = null
    private var pending: Pair<PlaybackQueueScope, PlaybackQueueState>? = null
    private var revision = 0L

    fun stage(scope: PlaybackQueueScope, items: List<PlaybackQueueItem>, selectedId: String) {
        val distinct = items.distinctBy { it.id }
        val index = distinct.indexOfFirst { it.id == selectedId }
        pending = if (index >= 0) scope to PlaybackQueueState(distinct, index) else null
    }

    fun attach(owner: Any, scope: PlaybackQueueScope, initial: PlaybackQueueItem,
        onSelect: (PlaybackQueueItem) -> Unit, onCompleted: () -> Unit, onClosed: () -> Unit = {},
        autoAdvanceAllowed: Boolean = true): Boolean {
        val staged = pending?.takeIf { it.first == scope && it.second.items[it.second.index].id == initial.id }
        pending = null
        clear()
        this.owner = owner
        selectItem = onSelect
        completeItem = onCompleted
        closeSession = onClosed
        revision++
        _state.value = (staged?.second ?: PlaybackQueueState(listOf(initial), 0)).copy(autoAdvanceAllowed = autoAdvanceAllowed)
        return staged != null
    }

    fun owns(owner: Any): Boolean = this.owner === owner

    /** A slow directory/season result cannot replace a different source's queue. */
    fun update(owner: Any, items: List<PlaybackQueueItem>) {
        if (!owns(owner)) return
        val current = _state.value.items.getOrNull(_state.value.index) ?: return
        val distinct = items.distinctBy { it.id }
        val index = distinct.indexOfFirst { it.id == current.id }
        if (index >= 0) _state.value = _state.value.copy(items = distinct, index = index)
    }

    fun previous() { if (_state.value.hasPrevious) select(_state.value.index - 1) }
    fun next() { if (_state.value.hasNext) select(_state.value.index + 1) }
    fun replay(): Boolean {
        val item = _state.value.items.getOrNull(_state.value.index) ?: return false
        val play = selectItem ?: return false
        revision++
        play(item)
        return true
    }
    fun select(index: Int) {
        val state = _state.value
        if (owner == null || index !in state.items.indices || index == state.index) return
        revision++
        _state.value = state.copy(index = index)
        selectItem?.invoke(state.items[index])
    }

    fun completed(autoAdvance: Boolean) {
        val completedRevision = revision
        completeItem?.invoke()
        if (autoAdvance && _state.value.autoAdvanceAllowed && revision == completedRevision) next()
    }

    fun detach(owner: Any) { if (owns(owner)) clear() }
    fun clear() {
        val close = closeSession
        revision++
        owner = null
        selectItem = null
        completeItem = null
        closeSession = null
        _state.value = PlaybackQueueState()
        close?.invoke()
    }
}
