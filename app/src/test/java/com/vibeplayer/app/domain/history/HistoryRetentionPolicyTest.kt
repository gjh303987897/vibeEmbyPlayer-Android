package com.vibeplayer.app.domain.history

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryRetentionPolicyTest {
    @Test fun includesTodayAndHandlesLeapMonthAndYearBoundaries() {
        assertEquals("2026-10-18", HistoryRetentionPolicy.cutoffDate(LocalDate.parse("2026-10-18"), 1))
        assertEquals("2026-09-19", HistoryRetentionPolicy.cutoffDate(LocalDate.parse("2026-10-18"), 30))
        assertEquals("2024-02-29", HistoryRetentionPolicy.cutoffDate(LocalDate.parse("2024-03-01"), 2))
        assertEquals("2025-12-27", HistoryRetentionPolicy.cutoffDate(LocalDate.parse("2026-01-02"), 7))
    }

    @Test fun clampsCorruptPreferences() {
        assertEquals(1, HistoryRetentionPolicy.normalizeDays(Int.MIN_VALUE))
        assertEquals(3650, HistoryRetentionPolicy.normalizeDays(Int.MAX_VALUE))
    }

    @Test fun dayCheckKeepsExistingUtcArchiveConvention() {
        val clock = Clock.fixed(Instant.parse("2026-10-18T23:59:59.500Z"), ZoneOffset.ofHours(8))
        assertEquals(LocalDate.parse("2026-10-18"), HistoryRetentionPolicy.today(clock))
        assertEquals(500, HistoryRetentionPolicy.nextDayCheckDelayMillis(clock))
        assertEquals(60_000, HistoryRetentionPolicy.nextDayCheckDelayMillis(
            Clock.fixed(Instant.parse("2026-10-18T12:00:00Z"), ZoneOffset.UTC)))
    }
}
