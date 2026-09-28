package com.vibeplayer.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.vibeplayer.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Privacy PIN management. The PIN is never stored in plain text; a salted
 * PBKDF2 hash lives in EncryptedSharedPreferences (Keystore-backed). Privacy
 * mode gates the visibility of private service cards, history and usage stats
 * at the repository layer.
 */
@Singleton
class PrivacyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope
) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            masterKey,
            FILE_NAME,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _privacyMode = MutableStateFlow(false)
    val privacyMode: StateFlow<Boolean> = _privacyMode.asStateFlow()
    private val _privatePlaybackStopped = MutableStateFlow(false)
    val privatePlaybackStopped: StateFlow<Boolean> = _privatePlaybackStopped.asStateFlow()
    private var failedAttempts = 0
    private var lockedUntilMs = 0L
    private var relockJob: Job? = null

    fun isPinConfigured(): Boolean = !prefs.getString(KEY_HASH, null).isNullOrEmpty()

    fun isPinCoolingDown(): Boolean = System.currentTimeMillis() < lockedUntilMs

    fun setPin(pin: String): Boolean {
        if (!isValidPin(pin)) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltStr = Base64.getEncoder().encodeToString(salt)
        prefs.edit()
            .putString(KEY_SALT, saltStr)
            .putString(KEY_HASH, pbkdf2Hash(pin, saltStr))
            .apply()
        return true
    }

    fun verifyPin(pin: String): Boolean {
        if (System.currentTimeMillis() < lockedUntilMs) return false
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        val valid = if (expected.startsWith(PBKDF2_PREFIX)) {
            pbkdf2Hash(pin, salt) == expected
        } else {
            legacySha256Hash(pin, salt) == expected
        }
        if (valid) {
            failedAttempts = 0
            if (!expected.startsWith(PBKDF2_PREFIX)) {
                prefs.edit().putString(KEY_HASH, pbkdf2Hash(pin, salt)).apply()
            }
        } else if (++failedAttempts >= MAX_FAILED_ATTEMPTS) {
            failedAttempts = 0
            lockedUntilMs = System.currentTimeMillis() + LOCKOUT_MS
        }
        return valid
    }

    /** Verifies the PIN and, on success, enters privacy mode. */
    fun openPrivacy(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        _privacyMode.value = true
        return true
    }

    fun exitPrivacyMode() {
        _privacyMode.value = false
        relockJob?.cancel()
    }

    fun scheduleBackgroundRelock() {
        relockJob?.cancel()
        relockJob = appScope.launch {
            delay(LOCK_AFTER_BACKGROUND_MS)
            exitPrivacyMode()
        }
    }

    fun cancelBackgroundRelock() {
        relockJob?.cancel()
        relockJob = null
    }

    fun onTaskRemoved() {
        cancelBackgroundRelock()
        exitPrivacyMode()
    }

    fun notifyPrivatePlaybackStopped() {
        _privatePlaybackStopped.value = true
    }

    fun acknowledgePrivatePlaybackStopped() {
        _privatePlaybackStopped.value = false
    }

    /** PBKDF2-HMAC-SHA256 derivation — the recommended, slow KDF for PINs. */
    private fun pbkdf2Hash(pin: String, salt: String): String {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            Base64.getDecoder().decode(salt),
            PBKDF2_ITERATIONS,
            256
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return PBKDF2_PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    /** Legacy salted SHA-256 used before the PBKDF2 migration (kept for verification). */
    private fun legacySha256Hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + pin).toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun isValidPin(pin: String) = pin.length in 4..16 && pin.all { it.isDigit() }

    companion object {
        private const val FILE_NAME = "vibeplayer_privacy"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_HASH = "pin_hash"
        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 210_000
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_MS = 30_000L
        private const val LOCK_AFTER_BACKGROUND_MS = 60_000L
    }
}
