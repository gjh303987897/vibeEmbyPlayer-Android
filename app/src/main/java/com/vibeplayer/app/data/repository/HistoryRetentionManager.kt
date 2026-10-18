package com.vibeplayer.app.data.repository

import android.util.Log
import com.vibeplayer.app.data.local.datastore.SettingsDataStore
import com.vibeplayer.app.data.local.db.VibePlayerDatabase
import com.vibeplayer.app.domain.history.HistoryRetentionPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-scoped retention coordinator; no player or privacy state participates in this policy. */
@Singleton
class HistoryRetentionManager @Inject constructor(
    private val settings: SettingsDataStore,
    private val database: VibePlayerDatabase
) {
    private val currentDay = MutableStateFlow(HistoryRetentionPolicy.today())
    private val pruneMutex = Mutex()
    private var settingsJob: Job? = null
    private var foregroundJob: Job? = null

    val cutoffDate: Flow<String> = combine(settings.historyRetentionDays, currentDay) { days, today ->
        HistoryRetentionPolicy.cutoffDate(today, days)
    }.distinctUntilChanged()

    /** Collecting immediately cleans existing installations and reacts to settings changes. */
    fun start(scope: CoroutineScope) {
        if (settingsJob?.isActive == true) return
        settingsJob = scope.launch {
            cutoffDate.collect { cutoff ->
                try {
                    pruneMutex.withLock { database.historyRetentionDao().pruneBefore(cutoff) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e("HistoryRetention", "Unable to clean expired history (${error.javaClass.simpleName})")
                }
            }
        }
    }

    fun onForeground(scope: CoroutineScope) {
        foregroundJob?.cancel()
        currentDay.value = HistoryRetentionPolicy.today()
        foregroundJob = scope.launch {
            // Retry startup cleanup after a temporary database failure even if the day did not change.
            try {
                pruneExpired()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("HistoryRetention", "Unable to refresh history retention (${error.javaClass.simpleName})")
            }
            while (isActive) {
                delay(HistoryRetentionPolicy.nextDayCheckDelayMillis())
                currentDay.value = HistoryRetentionPolicy.today()
            }
        }
    }

    fun onBackground() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    /** Settings UI awaits this call, so returning means the new limit has been applied to all tables. */
    suspend fun setRetentionDays(days: Int) = pruneMutex.withLock {
        settings.setHistoryRetentionDays(days)
        currentDay.value = HistoryRetentionPolicy.today()
        val cutoff = HistoryRetentionPolicy.cutoffDate(currentDay.value, HistoryRetentionPolicy.normalizeDays(days))
        database.historyRetentionDao().pruneBefore(cutoff)
    }

    suspend fun pruneExpired() = pruneMutex.withLock {
        currentDay.value = HistoryRetentionPolicy.today()
        val cutoff = HistoryRetentionPolicy.cutoffDate(currentDay.value, settings.historyRetentionDays.first())
        database.historyRetentionDao().pruneBefore(cutoff)
    }
}
