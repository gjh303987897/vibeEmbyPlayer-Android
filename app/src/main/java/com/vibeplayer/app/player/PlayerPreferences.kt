package com.vibeplayer.app.player

import com.vibeplayer.app.model.IptvQualityPreference
import com.vibeplayer.app.model.PlayerLayout

enum class VideoDecodeMode { HARDWARE, SOFTWARE }

data class PlayerPreferences(
    val longPressSpeed: Float = 2f,
    val showPerformance: Boolean = false,
    val showDiagnostics: Boolean = false,
    val layout: PlayerLayout = PlayerLayout.TRENDY,
    val videoDecodeMode: VideoDecodeMode = VideoDecodeMode.HARDWARE,
    val iptvQuality: IptvQualityPreference = IptvQualityPreference.HIGHEST,
    val subtitleAppearance: SubtitleAppearance = SubtitleAppearance(),
    val videoScaleMode: VideoScaleMode = VideoScaleMode.FIT,
    val autoPictureInPicture: Boolean = true
)

val LONG_PRESS_SPEEDS = listOf(1.25f, 1.5f, 2f, 2.5f, 3f, 4f)
val PLAYBACK_SPEEDS = listOf(.5f, 1f, 1.25f, 1.5f, 2f)
