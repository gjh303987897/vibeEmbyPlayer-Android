package com.vibeplayer.app.player

import com.vibeplayer.app.model.PlaybackSource
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    private val scope = PlaybackQueueScope(PlaybackSource.WEBDAV, "server")
    private val items = (1..3).map { PlaybackQueueItem("$it", "Episode $it") }

    @Test fun folderSnapshotPreservesOrderAndSelectionAndDeduplicatesIds() {
        val queue = PlaybackQueue()
        queue.stage(scope, items + items[1], "2")
        assertTrue(queue.attach(this, scope, items[1], {}, {}))
        assertEquals(items, queue.state.value.items)
        assertEquals(1, queue.state.value.index)
        assertTrue(queue.state.value.hasPrevious)
        assertTrue(queue.state.value.hasNext)
    }

    @Test fun manualSkippingRespectsBoundariesAndDoesNotCompleteSkippedVideos() {
        val queue = PlaybackQueue()
        val selections = mutableListOf<String>()
        var completions = 0
        queue.stage(scope, items, "1")
        queue.attach(this, scope, items[0], { selections += it.id }, { completions++ })
        queue.previous()
        queue.select(-1)
        queue.select(9)
        queue.select(0)
        assertTrue(selections.isEmpty())
        queue.next(); queue.next(); queue.next(); queue.previous()
        assertEquals(listOf("2", "3", "2"), selections)
        assertEquals(0, completions)
    }

    @Test fun completionCommitsOldItemBeforeAutoplayAndStopsAtQueueEnd() {
        val events = mutableListOf<String>()
        val queue = PlaybackQueue()
        queue.stage(scope, items, "2")
        queue.attach(this, scope, items[1], { events += "play ${it.id}" }, { events += "complete ${queue.state.value.index}" })
        queue.completed(false)
        assertEquals(1, queue.state.value.index)
        queue.completed(true)
        assertEquals(listOf("complete 1", "complete 1", "play 3"), events)
        queue.completed(true)
        assertEquals(2, queue.state.value.index)
        assertFalse(queue.state.value.hasNext)
    }

    @Test fun liveChannelsNeverAutomaticallySwitchButAllowManualNext() {
        val queue = PlaybackQueue()
        queue.stage(scope, items, "1")
        queue.attach(this, scope, items[0], {}, {}, autoAdvanceAllowed = false)
        queue.completed(true)
        assertEquals(0, queue.state.value.index)
        queue.next()
        assertEquals(1, queue.state.value.index)
    }

    @Test fun staleOwnerAndLateDirectoryResultsCannotAffectNewPlayback() {
        val queue = PlaybackQueue()
        val old = Any()
        var closed = 0
        queue.attach(old, scope, items[0], {}, {}, onClosed = { closed++ })
        queue.attach(this, scope, items[2], {}, {})
        queue.update(old, items)
        queue.detach(old)
        assertEquals(1, closed)
        assertTrue(queue.owns(this))
        assertEquals(listOf(items[2]), queue.state.value.items)
        queue.update(this, items)
        assertEquals(2, queue.state.value.index)
        queue.update(this, listOf(items[0]))
        assertEquals(items, queue.state.value.items)
    }

    @Test fun completionWhichClosesOrReplacesTheQueueCannotAdvanceItsReplacement() {
        val queue = PlaybackQueue()
        queue.stage(scope, items, "1")
        queue.attach(this, scope, items[0], {}, { queue.attach(Any(), scope, items[2], {}, {}) })
        queue.completed(true)
        assertEquals(listOf(items[2]), queue.state.value.items)
        assertEquals(0, queue.state.value.index)
    }

    @Test fun staleSnapshotForDifferentServerOrInitialFileIsNotReused() {
        val queue = PlaybackQueue()
        queue.stage(scope, items, "2")
        assertFalse(queue.attach(this, scope.copy(serviceId = "other"), items[1], {}, {}))
        assertEquals(listOf(items[1]), queue.state.value.items)
        queue.stage(scope, items, "2")
        assertFalse(queue.attach(this, scope, items[0], {}, {}))
        assertEquals(listOf(items[0]), queue.state.value.items)
    }

    @Test fun replayRestartsCurrentItemAndClearDetachesCallbacks() {
        val queue = PlaybackQueue()
        var played = 0
        queue.attach(this, scope, items[0], { played++ }, {})
        assertTrue(queue.replay())
        assertEquals(1, played)
        queue.clear()
        queue.next(); queue.completed(true)
        assertFalse(queue.replay())
        assertEquals(PlaybackQueueState(), queue.state.value)
        assertEquals(1, played)
    }
}
