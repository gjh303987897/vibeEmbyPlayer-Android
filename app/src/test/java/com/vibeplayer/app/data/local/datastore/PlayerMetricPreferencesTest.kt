package com.vibeplayer.app.data.local.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.vibeplayer.app.player.PlayerMetric
import com.vibeplayer.app.player.PlayerMetricVisibility
import org.junit.Assert.*
import org.junit.Test

class PlayerMetricPreferencesTest {
    @Test fun `fresh installs show no metrics`() {
        assertEquals(PlayerMetricVisibility(), readPlayerMetricVisibility(emptyPreferences()))
    }

    @Test fun `legacy grouped preferences preserve all four old combinations`() {
        for (performance in listOf(false, true)) for (diagnostics in listOf(false, true)) {
            val stored = mutablePreferencesOf(
                booleanPreferencesKey("player_show_performance") to performance,
                booleanPreferencesKey("player_show_diagnostics") to diagnostics
            )
            assertEquals(PlayerMetricVisibility(performance, performance, diagnostics, diagnostics), readPlayerMetricVisibility(stored))
        }
    }

    @Test fun `writing any individual flag never changes its siblings or other settings`() {
        for (mask in 0..15) for ((index, metric) in PlayerMetric.entries.withIndex()) {
            val stored = mutablePreferencesOf(booleanPreferencesKey("unrelated_setting") to true)
            PlayerMetric.entries.forEachIndexed { bit, item -> stored.setPlayerMetricVisible(item, mask and (1 shl bit) != 0) }
            stored.setPlayerMetricVisible(metric, mask and (1 shl index) == 0)
            val read = readPlayerMetricVisibility(stored)
            PlayerMetric.entries.forEachIndexed { bit, item ->
                val expected = (mask and (1 shl bit) != 0) xor (item == metric)
                assertEquals("mask=$mask changed=$metric read=$item", expected, read.isEnabled(item))
            }
            assertEquals(true, stored[booleanPreferencesKey("unrelated_setting")])
        }
    }

    @Test fun `explicit false overrides legacy true without reactivating siblings on reread`() {
        val stored = mutablePreferencesOf(
            booleanPreferencesKey("player_show_performance") to true,
            booleanPreferencesKey("player_show_diagnostics") to true
        )
        stored.setPlayerMetricVisible(PlayerMetric.GPU, false)
        stored.setPlayerMetricVisible(PlayerMetric.NETWORK_SPEED, false)
        assertEquals(PlayerMetricVisibility(cpu = true, frameRate = true), readPlayerMetricVisibility(stored))
        stored.setPlayerMetricVisible(PlayerMetric.CPU, false)
        stored.setPlayerMetricVisible(PlayerMetric.FRAME_RATE, false)
        assertEquals(PlayerMetricVisibility(), readPlayerMetricVisibility(stored))
        assertEquals(true, stored[booleanPreferencesKey("player_show_performance")])
        assertEquals(true, stored[booleanPreferencesKey("player_show_diagnostics")])
    }
}
