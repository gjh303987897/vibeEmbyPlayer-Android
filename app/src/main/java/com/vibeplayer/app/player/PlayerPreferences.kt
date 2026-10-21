package com.vibeplayer.app.player

import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.PlayerLayout

enum class VideoDecodeMode { HARDWARE, SOFTWARE }

enum class PlayerMetric { CPU, GPU, FRAME_RATE, NETWORK_SPEED }

data class PlayerMetricVisibility(
    val cpu: Boolean = false,
    val gpu: Boolean = false,
    val frameRate: Boolean = false,
    val networkSpeed: Boolean = false
) {
    fun isEnabled(metric: PlayerMetric): Boolean = when (metric) {
        PlayerMetric.CPU -> cpu
        PlayerMetric.GPU -> gpu
        PlayerMetric.FRAME_RATE -> frameRate
        PlayerMetric.NETWORK_SPEED -> networkSpeed
    }
}

data class PlayerPreferences(
    val longPressSpeed: Float = 2f,
    val metrics: PlayerMetricVisibility = PlayerMetricVisibility(),
    val autoPlayNext: Boolean = true,
    val layout: PlayerLayout = PlayerLayout.TRENDY,
    val videoDecodeMode: VideoDecodeMode = VideoDecodeMode.HARDWARE,
    val iptvQuality: IptvQualityPreference = IptvQualityPreference.HIGHEST,
    val subtitleAppearance: SubtitleAppearance = SubtitleAppearance(),
    val videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val autoPictureInPicture: Boolean = true
) {
    val showPerformance: Boolean get() = metrics.cpu || metrics.gpu
    val showDiagnostics: Boolean get() = metrics.frameRate || metrics.networkSpeed
}

val LONG_PRESS_SPEEDS = listOf(1.25f, 1.5f, 2f, 2.5f, 3f, 4f)
val PLAYBACK_SPEEDS = listOf(.5f, 1f, 1.25f, 1.5f, 2f)
