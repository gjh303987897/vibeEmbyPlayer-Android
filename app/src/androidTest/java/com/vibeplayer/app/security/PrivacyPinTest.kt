package com.vibeplayer.app.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyPinTest {
    @Test fun configuredPinRequiresOldPinAndKeepsLockout() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "pin-regression-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = PrivacyManager(isolated, scope)
            assertTrue(manager.setPin("1234"))
            assertFalse(manager.setPin("9999"))
            assertFalse(manager.changePin("0000", "9999"))
            assertTrue(manager.verifyPin("1234"))
            assertTrue(manager.changePin("1234", "5678"))
            assertFalse(manager.privacyMode.value)
            assertFalse(manager.verifyPin("1234"))
            assertTrue(manager.openPrivacy("5678"))
            manager.exitPrivacyMode()
            repeat(5) { assertFalse(manager.verifyPin("0000")) }
            assertTrue(manager.isPinCoolingDown())
            assertFalse(manager.changePin("5678", "9999"))
            assertFalse(manager.setPin("9999"))
            assertFalse(manager.privacyMode.value)
        } finally {
            scope.cancel()
            base.deleteSharedPreferences(prefix + "vibeplayer_privacy")
        }
    }
}
