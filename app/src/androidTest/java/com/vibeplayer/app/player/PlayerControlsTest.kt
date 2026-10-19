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

    @Test fun pinchZoomDoesNotSeekChangeVolumeTogglePlaybackOrDimTheWindow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: View
            lateinit var window: Window
            val zoom = mutableStateOf(1f)
            var taps = 0
            var playbackToggles = 0
            var seeks = 0
            var volumeChanges = 0
            var boosts = 0
            val laidOut = CountDownLatch(1)
            scenario.onActivity { activity ->
                root = activity.window.decorView
                window = activity.window
                activity.setContent {
                    val gesture = rememberPlayerGestureState()
                    Box(Modifier.fillMaxSize().onGloballyPositioned {
                        surfaceBounds = it.boundsInWindow()
                        laidOut.countDown()
                    }.playerGestureSurface(gesture, { taps++ }, { playbackToggles++ },
                        { boosts++ }, {}, 10_000, 600_000, { seeks++ }, .5f, { volumeChanges++ },
                        zoomScale = zoom.value, onZoomChange = { zoom.value = it }))
                }
            }
            assertTrue(laidOut.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val brightness = window.attributes.screenBrightness
            val down = SystemClock.uptimeMillis()
            multiTouch(root, down, MotionEvent.ACTION_DOWN, listOf(.4f to .5f))
            multiTouch(root, down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(.4f to .5f, .6f to .5f))
            multiTouch(root, down, MotionEvent.ACTION_MOVE, listOf(.25f to .5f, .75f to .5f))
            multiTouch(root, down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                listOf(.25f to .5f, .75f to .5f))
            multiTouch(root, down, MotionEvent.ACTION_UP, listOf(.25f to .5f))
            Thread.sleep(400) // Include the single-tap deadline, not only the immediate release.
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("two-finger spread must zoom", zoom.value > 1.2f)
                assertEquals(0, taps)
                assertEquals(0, playbackToggles)
                assertEquals(0, seeks)
                assertEquals(0, volumeChanges)
                assertEquals(0, boosts)
                assertEquals(brightness, window.attributes.screenBrightness, .0001f)
            }
        }
    }

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

    private fun multiTouch(root: View, down: Long, action: Int, points: List<Pair<Float, Float>>) {
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            root.getLocationInWindow(location)
            val bounds = requireNotNull(surfaceBounds)
            val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
                x = bounds.left + points[index].first * bounds.width - location[0]
                y = bounds.top + points[index].second * bounds.height - location[1]
                pressure = 1f
                size = 1f
            } }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size, properties,
                coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
