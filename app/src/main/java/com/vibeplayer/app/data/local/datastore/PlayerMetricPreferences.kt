package com.vibeplayer.app.data.local.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.vibeplayer.app.player.PlayerMetric
import com.vibeplayer.app.player.PlayerMetricVisibility

private val metricKeys = mapOf(
    PlayerMetric.CPU to booleanPreferencesKey("player_show_cpu"),
    PlayerMetric.GPU to booleanPreferencesKey("player_show_gpu"),
    PlayerMetric.FRAME_RATE to booleanPreferencesKey("player_show_fps"),
    PlayerMetric.NETWORK_SPEED to booleanPreferencesKey("player_show_network_speed")
)
private val legacyPerformance = booleanPreferencesKey("player_show_performance")
private val legacyDiagnostics = booleanPreferencesKey("player_show_diagnostics")

/** Explicit individual values, including false, take precedence over the old grouped flags. */
internal fun readPlayerMetricVisibility(preferences: Preferences): PlayerMetricVisibility {
    fun read(metric: PlayerMetric, legacy: Preferences.Key<Boolean>) =
        preferences[metricKeys.getValue(metric)] ?: preferences[legacy] ?: false
    return PlayerMetricVisibility(
        cpu = read(PlayerMetric.CPU, legacyPerformance),
        gpu = read(PlayerMetric.GPU, legacyPerformance),
        frameRate = read(PlayerMetric.FRAME_RATE, legacyDiagnostics),
        networkSpeed = read(PlayerMetric.NETWORK_SPEED, legacyDiagnostics)
    )
}

internal fun MutablePreferences.setPlayerMetricVisible(metric: PlayerMetric, enabled: Boolean) {
    this[metricKeys.getValue(metric)] = enabled
}
