package com.vibeplayer.app.domain.history

import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/** Calendar-day retention, including today, using the existing Android UTC archive dates. */
object HistoryRetentionPolicy {
    const val DEFAULT_DAYS = 30
    const val MIN_DAYS = 1
    const val MAX_DAYS = 3650

    fun normalizeDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    fun cutoffDate(today: LocalDate, days: Int): String =
        today.minusDays(normalizeDays(days).toLong() - 1L).toString()

    fun today(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    /** Short polling also notices a manually changed system date while the app remains foreground. */
    fun nextDayCheckDelayMillis(clock: Clock = Clock.systemUTC()): Long {
        val nextMidnight = today(clock).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return Duration.between(clock.instant(), nextMidnight).toMillis().coerceIn(1L, 60_000L)
    }
}
