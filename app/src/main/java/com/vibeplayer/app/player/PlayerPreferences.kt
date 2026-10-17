package com.vibeplayer.app.player

import com.vibeplayer.app.model.IptvQualityPreference

enum class VideoDecodeMode { HARDWARE, SOFTWARE }

data class PlayerPreferences(
    val longPressSpeed: Float = 2f,
    val showPerformance: Boolean = false,
    val videoDecodeMode: VideoDecodeMode = VideoDecodeMode.HARDWARE,
    val iptvQuality: IptvQualityPreference = IptvQualityPreference.HIGHEST
)

val LONG_PRESS_SPEEDS = listOf(1.25f, 1.5f, 2f, 2.5f, 3f, 4f)
