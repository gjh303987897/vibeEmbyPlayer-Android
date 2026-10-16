package com.vibeplayer.app.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/** One volume scale for surface gestures, the slider and the device media keys. */
internal class PlaybackVolumeController(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    val volume: Float
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun setVolume(fraction: Float): Float {
        if (!fraction.isFinite() || audio.isVolumeFixed) return volume
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction.coerceIn(0f, 1f) * maximum).roundToInt(), 0)
        return volume
    }
}
