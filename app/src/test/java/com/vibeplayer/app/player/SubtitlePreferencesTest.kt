package com.vibeplayer.app.player

import com.vibeplayer.app.domain.subtitle.TextSubtitleCue
import com.vibeplayer.app.domain.subtitle.TextSubtitleTimeline
import org.junit.Assert.*
import org.junit.Test

class SubtitlePreferencesTest {
    private val timeline = TextSubtitleTimeline(listOf(
        TextSubtitleCue(2_000, 4_000, "first"), TextSubtitleCue(6_000, 8_000, "second")
    ))

    @Test fun positiveDelayMovesStartAndEndLaterAndNegativeDelayMovesThemEarlier() {
        assertNull(timeline.textAt(subtitlePositionMs(3_999, 2_000)))
        assertEquals("first", timeline.textAt(subtitlePositionMs(4_000, 2_000)))
        assertNull(timeline.textAt(subtitlePositionMs(6_000, 2_000)))
        assertEquals("first", timeline.textAt(subtitlePositionMs(0, -2_000)))
        assertNull(timeline.textAt(subtitlePositionMs(2_000, -2_000)))
        assertEquals("second", timeline.textAt(subtitlePositionMs(4_000, -2_000)))
    }

    @Test fun returningOffsetToZeroUsesTheOriginalTimelineWithoutEditingCues() {
        assertNull(timeline.textAt(subtitlePositionMs(3_000, 2_000)))
        assertEquals("first", timeline.textAt(subtitlePositionMs(3_000, 0)))
        assertNull(timeline.textAt(subtitlePositionMs(3_000, -2_000)))
        assertEquals("first", timeline.textAt(subtitlePositionMs(3_000, 0)))
    }

    @Test fun delayedCueAtZeroDoesNotAppearEarlyAndBoundaryArithmeticDoesNotOverflow() {
        val first = TextSubtitleTimeline(listOf(TextSubtitleCue(0, 1_000, "start")))
        assertNull(first.textAt(subtitlePositionMs(0, 500)))
        assertEquals("start", first.textAt(subtitlePositionMs(500, 500)))
        assertEquals(Long.MAX_VALUE, subtitlePositionMs(Long.MAX_VALUE, -10_000))
        assertEquals(-10_000L, subtitlePositionMs(0, Long.MAX_VALUE))
        assertEquals(10_000L, subtitlePositionMs(0, Long.MIN_VALUE))
    }

    @Test fun corruptedAppearanceValuesAreBoundedWithoutAffectingDefaults() {
        assertEquals(SubtitleAppearance(36, 0, 100), SubtitleAppearance(500, -20, 200).normalized())
        assertEquals(SubtitleAppearance(12, 120, 0), SubtitleAppearance(0, 500, -5).normalized())
        assertEquals(SubtitleAppearance(), SubtitleAppearance().normalized())
    }
}
