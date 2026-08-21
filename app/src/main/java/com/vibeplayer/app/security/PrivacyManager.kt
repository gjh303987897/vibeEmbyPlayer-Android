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

/**
 * Privacy PIN management. The PIN is never stored in plain text; a salted
 * SHA-256 hash lives in EncryptedSharedPreferences (Keystore-backed). Privacy
 * mode gates the visibility of private service cards, history and usage stats
 * at the repository layer.
 */
@Singleton
class PrivacyManager @Inject constructor(
    @ApplicationContext private val context: Context
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

    fun isPinConfigured(): Boolean = !prefs.getString(KEY_HASH, null).isNullOrEmpty()

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
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        // Support PINs stored before the PBKDF2 migration (plain salted SHA-256).
        return if (expected.startsWith(PBKDF2_PREFIX)) {
            pbkdf2Hash(pin, salt) == expected
        } else {
            legacySha256Hash(pin, salt) == expected
        }
    }

    /** Verifies the PIN and, on success, enters privacy mode. */
    fun openPrivacy(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        _privacyMode.value = true
        return true
    }

    fun enterPrivacyMode() {
        _privacyMode.value = true
    }

    fun exitPrivacyMode() {
        _privacyMode.value = false
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
    }
}
