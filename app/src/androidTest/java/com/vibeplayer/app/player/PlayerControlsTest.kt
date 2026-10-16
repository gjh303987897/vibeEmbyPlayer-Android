package com.vibeplayer.app.player

import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vibeplayer.app.MainActivity
import com.vibeplayer.app.VibePlayerApp
import com.vibeplayer.app.ui.player.PlayerScreenLock
import com.vibeplayer.app.ui.player.playerGestureSurface
import com.vibeplayer.app.ui.player.rememberPlayerGestureState
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var surfaceBounds: Rect? = null

    @Test fun rightSwipeChangesSystemMediaVolumeAndLockPreservesBrightness() {
        val audio = instrumentation.targetContext.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val locked = mutableStateOf(false)
                lateinit var root: View
                lateinit var window: Window
                lateinit var manager: PlayerManager
                val laidOut = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    root = activity.window.decorView
                    window = activity.window
                    manager = (activity.application as VibePlayerApp).playerManager
                    activity.setContent {
                        val state by manager.state.collectAsState()
                        val gestures = rememberPlayerGestureState()
                        MaterialTheme {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.fillMaxSize().onGloballyPositioned {
                                    surfaceBounds = it.boundsInWindow()
                                    laidOut.countDown()
                                }.playerGestureSurface(
                                    gestures, {}, {}, manager::beginTemporarySpeed, manager::endTemporarySpeed,
                                    0, 600_000, {}, state.volume, manager::setVolume, enabled = !locked.value
                                ))
                                PlayerScreenLock(locked.value, true) { locked.value = !locked.value }
                            }
                        }
                    }
                    manager.setVolume(0f)
                }
                assertTrue("gesture surface must finish its first layout", laidOut.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                swipe(root, .85f, .7f, .85f, .3f)
                assertTrue("right swipe must raise the actual music stream from mute",
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0)
                swipe(root, .85f, .3f, .85f, .8f)
                assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

                swipe(root, .25f, .3f, .25f, .65f)
                var brightness = -1f
                scenario.onActivity { brightness = it.window.attributes.screenBrightness }
                assertTrue("left swipe must establish a window brightness override", brightness in .08f..1f)
                tapLock(root)
                scenario.onActivity {
                    assertTrue("the lock button must lock touches", locked.value)
                    assertEquals("locking must not restore system brightness", brightness, it.window.attributes.screenBrightness, .0001f)
                }
                tapLock(root)
                scenario.onActivity {
                    assertFalse(locked.value)
                    assertEquals(brightness, it.window.attributes.screenBrightness, .0001f)
                    it.setContent { }
                }
                awaitCondition("leaving the gesture surface must restore system brightness") {
                    window.attributes.screenBrightness == -1f
                }
            }
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    @Test fun configuredLongPressSpeedRestoresThePreviousSpeedOnRelease() {
        val app = instrumentation.targetContext.applicationContext as VibePlayerApp
        val original = app.playerManager.preferences.value.longPressSpeed
        try {
            runBlocking { app.settingsDataStore.setLongPressSpeed(3f) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var root: View
                val laidOut = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    root = activity.window.decorView
                    app.playerManager.setPlaybackSpeed(1.25f)
                    activity.setContent {
                        val preferences by app.playerManager.preferences.collectAsState()
                        val gestures = rememberPlayerGestureState()
                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                            surfaceBounds = it.boundsInWindow()
                            laidOut.countDown()
                        }.playerGestureSurface(
                            gestures, {}, {}, app.playerManager::beginTemporarySpeed, app.playerManager::endTemporarySpeed,
                            0, 600_000, {}, 1f, {}, speedWhilePressed = preferences.longPressSpeed
                        ))
                    }
                }
                assertTrue("gesture surface must finish its first layout", laidOut.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val down = SystemClock.uptimeMillis()
                touch(root, MotionEvent.ACTION_DOWN, .5f, .5f, down)
                try {
                    SystemClock.sleep(800)
                    scenario.onActivity { assertEquals(3f, app.playerManager.state.value.playbackSpeed, .001f) }
                } finally {
                    touch(root, MotionEvent.ACTION_UP, .5f, .5f, down)
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { assertEquals(1.25f, app.playerManager.state.value.playbackSpeed, .001f) }
            }
        } finally {
            instrumentation.runOnMainSync { app.playerManager.setPlaybackSpeed(1f) }
            runBlocking { app.settingsDataStore.setLongPressSpeed(original) }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = condition() }
            if (matched) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError(message)
    }

    private fun swipe(root: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        touch(root, MotionEvent.ACTION_DOWN, x1, y1, down)
        for (step in 1..12) {
            SystemClock.sleep(15)
            val fraction = step / 12f
            touch(root, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, down)
        }
        touch(root, MotionEvent.ACTION_UP, x2, y2, down)
        instrumentation.waitForIdleSync()
    }

    private fun tapLock(root: View) {
        val x = 36 * root.resources.displayMetrics.density / root.width
        val down = SystemClock.uptimeMillis()
        touch(root, MotionEvent.ACTION_DOWN, x, .5f, down)
        touch(root, MotionEvent.ACTION_UP, x, .5f, down)
        instrumentation.waitForIdleSync()
    }

    private fun touch(root: View, action: Int, x: Float, y: Float, down: Long) {
        // Dispatch through the real view hierarchy without UiAutomation's window-animation
        // wait ageing ACTION_DOWN enough to trigger the long-press deadline before a move.
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val bounds = surfaceBounds ?: Rect(
                location[0].toFloat(), location[1].toFloat(),
                location[0] + root.width.toFloat(), location[1] + root.height.toFloat()
            )
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width * x - location[0],
                bounds.top + bounds.height * y - location[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("the player view must handle the touch", root.dispatchTouchEvent(event)) }
            finally { event.recycle() }
        }
    }
}
