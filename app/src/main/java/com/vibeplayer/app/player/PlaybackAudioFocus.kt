package com.vibeplayer.app.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat

/** Owns focus and route interruptions for the single shared player. All callbacks use main. */
@MainThread
internal class PlaybackAudioFocus(
    private val context: Context,
    private val onPauseForLoss: () -> Unit,
    private val onResumeAfterGain: () -> Unit,
    private val onDuckingChanged: (Float) -> Unit
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var activeRequest = false
    private var granted = false
    private var resumeAfterGain = false
    private var receiverRegistered = false

    var volumeMultiplier: Float = 1f
        private set

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && activeRequest) {
                onPauseForLoss()
                abandon()
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        )
        .setAcceptsDelayedFocusGain(true)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChanged, Handler(Looper.getMainLooper()))
        .build()

    fun request(): Boolean {
        if (activeRequest) return granted
        activeRequest = true
        when (audioManager.requestAudioFocus(focusRequest)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                granted = true
                resumeAfterGain = false
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                granted = false
                resumeAfterGain = true
            }
            else -> {
                abandon()
                return false
            }
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                context, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        return granted
    }

    fun abandon() {
        val hadRequest = activeRequest
        activeRequest = false
        granted = false
        resumeAfterGain = false
        if (receiverRegistered) {
            context.unregisterReceiver(noisyReceiver)
            receiverRegistered = false
        }
        if (hadRequest) audioManager.abandonAudioFocusRequest(focusRequest)
        setDucking(1f)
    }

    private fun onFocusChanged(change: Int) {
        if (!activeRequest) return
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                granted = true
                val shouldResume = resumeAfterGain
                resumeAfterGain = false
                setDucking(1f)
                if (shouldResume) onResumeAfterGain()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                granted = false
                resumeAfterGain = true
                onPauseForLoss()
                setDucking(1f)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setDucking(0.2f)
            AudioManager.AUDIOFOCUS_LOSS -> {
                onPauseForLoss()
                abandon()
            }
        }
    }

    private fun setDucking(multiplier: Float) {
        if (volumeMultiplier == multiplier) return
        volumeMultiplier = multiplier
        onDuckingChanged(multiplier)
    }
}
