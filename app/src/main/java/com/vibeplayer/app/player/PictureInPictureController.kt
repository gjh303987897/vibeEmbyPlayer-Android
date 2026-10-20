package com.vibeplayer.app.player

import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.vibeplayer.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Activity-scoped system/window integration. Playback remains in PlayerManager. */
class PictureInPictureController(private val activity: FragmentActivity, private val manager: PlayerManager) {
    private val _state = MutableStateFlow(PlayerPipState(inPictureInPicture = activity.isInPictureInPictureMode))
    val state = _state.asStateFlow()
    private val owners = mutableSetOf<Any>()
    private val autoEntryBlocks = mutableSetOf<Any>()
    private var externalUi = false
    private var allowed = false
    private var sourceBounds: Rect? = null
    private var lastParams: ParamsKey? = null
    private var wasInPip = activity.isInPictureInPictureMode
    private var closed = false
    private var entryTimeout: Job? = null
    private val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    private val observer = activity.lifecycleScope.launch {
        combine(manager.state, manager.preferences, manager.sleepTimerState) { _, _, _ -> Unit }
            .collect { refresh() }
    }

    private data class ParamsKey(val ratio: PipAspectRatio, val bounds: Rect?, val automatic: Boolean)

    fun attachPlayer(owner: Any) { owners += owner; refresh() }

    fun detachPlayer(owner: Any) {
        owners -= owner
        if (owners.isEmpty()) {
            sourceBounds = null
            // Never leave a library/settings page displayed in the video-only window.
            if (!closed && activity.isInPictureInPictureMode && !activity.isChangingConfigurations) {
                manager.pause()
                activity.finish()
            }
        }
        refresh()
    }

    fun addAutoEntryBlock(): Any = Any().also { autoEntryBlocks += it; refresh() }
    fun removeAutoEntryBlock(token: Any) { autoEntryBlocks -= token; refresh() }
    fun setExternalUiVisible(visible: Boolean) { externalUi = visible; refresh() }

    fun updateSourceBounds(bounds: Rect) {
        if (bounds.isEmpty || bounds == sourceBounds) return
        sourceBounds = Rect(bounds)
        refresh()
    }

    fun onResume() {
        if (closed) return
        allowed = supported && runCatching {
            val ops = activity.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), activity.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), activity.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        externalUi = false
        if (!activity.isInPictureInPictureMode) {
            wasInPip = false
            _state.update { it.copy(entering = false, inPictureInPicture = false) }
        }
        lastParams = null
        refresh()
    }

    fun onStop() {
        if (!closed && !activity.isChangingConfigurations && (wasInPip || activity.isInPictureInPictureMode)) {
            // Closing/hiding the PiP window must not leave audio playing in the service.
            manager.pause()
        }
    }

    fun onModeChanged(inPip: Boolean) {
        entryTimeout?.cancel()
        if (inPip) wasInPip = true
        else if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) wasInPip = false
        _state.update { it.copy(inPictureInPicture = inPip, entering = false) }
        if (inPip && owners.isEmpty() && !activity.isChangingConfigurations) {
            manager.pause()
            activity.finish()
        }
        refresh()
    }

    fun onTransitionChanged(entering: Boolean) {
        _state.update { it.copy(entering = entering) }
        if (entering) watchEntryResult()
    }

    fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && automaticAllowed()) enter(automatic = true)
    }

    fun enter(automatic: Boolean = false): Boolean {
        if (closed || activity.isFinishing || activity.isDestroyed || activity.isInPictureInPictureMode) return false
        refresh()
        if (!_state.value.canEnter || (automatic && !automaticAllowed())) return false
        _state.update { it.copy(entering = true) }
        val accepted = try {
            activity.enterPictureInPictureMode(buildParams())
        } catch (error: RuntimeException) {
            Log.w("PlayerPiP", "PiP entry failed (${error.javaClass.simpleName})")
            false
        }
        if (!accepted) {
            _state.update { it.copy(entering = false) }
            if (!automatic) Toast.makeText(activity, R.string.pip_enter_failed, Toast.LENGTH_SHORT).show()
        } else {
            watchEntryResult()
        }
        return accepted
    }

    private fun watchEntryResult() {
        entryTimeout?.cancel()
        entryTimeout = activity.lifecycleScope.launch {
            delay(2_000)
            if (!activity.isInPictureInPictureMode) _state.update { it.copy(entering = false) }
        }
    }

    fun openSystemSettings() {
        setExternalUiVisible(true)
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${activity.packageName}")))
        } catch (error: RuntimeException) {
            setExternalUiVisible(false)
            Toast.makeText(activity, R.string.pip_settings_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun eligibility(): PipPlaybackEligibility {
        val player = manager.state.value
        val hasVideo = player.isPrepared && runCatching {
            manager.player.videoWidth > 0 && manager.player.videoHeight > 0
        }.getOrDefault(false)
        return PipPlaybackEligibility(supported, allowed, owners.isNotEmpty(), hasVideo,
            player.isPrepared, player.isPlaying, player.privatePlayback, player.error != null,
            manager.sleepTimerState.value.expired)
    }

    private fun automaticAllowed() = eligibility().autoEnter(manager.preferences.value.autoPictureInPicture,
        externalUi || autoEntryBlocks.isNotEmpty())

    private fun paramsKey(): ParamsKey {
        val ratio = if (manager.state.value.isPrepared) runCatching {
            pipAspectRatio(manager.player.videoWidth, manager.player.videoHeight,
                manager.player.videoSarNum, manager.player.videoSarDen)
        }.getOrDefault(PipAspectRatio(16, 9)) else PipAspectRatio(16, 9)
        return ParamsKey(ratio, sourceBounds?.let(::Rect), automaticAllowed())
    }

    private fun buildParams(key: ParamsKey = paramsKey()): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(key.ratio.width, key.ratio.height))
        key.bounds?.let { builder.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(key.automatic).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun refresh() {
        if (closed) return
        _state.update { it.copy(supported = supported, entryBlock = eligibility().block) }
        if (!supported || activity.isFinishing || activity.isDestroyed) return
        if (activity.isInPictureInPictureMode && manager.state.value.privatePlayback) {
            manager.pause()
            activity.finish()
            return
        }
        val key = paramsKey()
        if (key == lastParams) return
        try {
            activity.setPictureInPictureParams(buildParams(key))
            lastParams = key
        } catch (error: RuntimeException) {
            Log.w("PlayerPiP", "PiP parameters unavailable (${error.javaClass.simpleName})")
        }
    }

    fun close() { closed = true; observer.cancel(); entryTimeout?.cancel(); owners.clear(); autoEntryBlocks.clear() }
}
