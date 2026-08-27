package com.vibeplayer.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small, self-healing secret store built directly on the Android Keystore.
 *
 * Values are encrypted with a non-exportable AES-256-GCM key that lives in the
 * hardware/TEE Keystore; only `base64(iv || ciphertext)` is written to a plain
 * SharedPreferences file. Nothing secret is ever stored in clear text and
 * nothing (key, IV, ciphertext) is ever logged.
 *
 * Why not `androidx.security.crypto.EncryptedSharedPreferences`? That library is
 * deprecated and fragile in exactly the place this app hurts the most: it
 * decrypts **every** entry while being created, and if its master key is
 * unusable (Keystore reset, OEM Keystore bug, `VERIFICATION_FAILED`) creation
 * throws once and the whole store stays permanently empty for the rest of the
 * process — so a saved Emby token / password silently can never be read back
 * and the user is asked for the password again and again.
 *
 * This implementation avoids that failure mode:
 *
 * - a *single* unreadable value only drops that value (never the whole store);
 * - an unusable key entry is deleted and regenerated, so the store recovers by
 *   itself on the next write instead of staying broken forever;
 * - failures are throttled instead of cached permanently, so a transient
 *   Keystore error does not disable the store for the session.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Timestamp until which key creation is retried (transient Keystore outage). */
    private var keyRetryAfterMs = 0L

    /** Cached Keystore key; Keystore lookups are not free, and one key serves all entries. */
    private var cachedKey: SecretKey? = null

    /** Reads and decrypts [key], or null when absent / unreadable. */
    fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX)) {
            // Foreign / legacy payload format: drop it instead of failing forever.
            Log.w(TAG, "dropping value with unknown format for key=$key")
            remove(key)
            return null
        }
        return try {
            val keyEntry = secretKey() ?: return null
            decrypt(keyEntry, stored.removePrefix(PREFIX))
        } catch (t: Throwable) {
            // The key was regenerated (or the value was tampered with): this one
            // entry can never be decrypted again, so discard just it.
            Log.w(TAG, "decrypt failed for key=$key (${t.javaClass.simpleName}), discarding entry")
            remove(key)
            // The current key cannot read what was written before it, so anything
            // else in the store is dead weight too: force a fresh key next time.
            cachedKey = null
            null
        }
    }

    /** Encrypts and stores [value]. Returns false when the Keystore is unavailable. */
    fun putString(key: String, value: String): Boolean = try {
        val keyEntry = secretKey() ?: return false
        val encoded = PREFIX + encode(encrypt(keyEntry, value))
        prefs.edit().putString(key, encoded).apply()
        true
    } catch (t: Throwable) {
        Log.e(TAG, "encrypt failed for key=$key (${t.javaClass.simpleName})")
        cachedKey = null
        false
    }

    /** Stores [value] only when [key] currently holds no usable value. */
    fun putStringIfAbsent(key: String, value: String): Boolean {
        if (getString(key) != null) return false
        return putString(key, value)
    }

    fun remove(key: String) {
        runCatching { prefs.edit().remove(key).apply() }
    }

    /**
     * Returns the Keystore AES key, creating it when missing.
     *
     * A `null` result means the Keystore is currently unavailable; the caller
     * degrades to "not stored / not readable" without crashing. A key entry that
     * exists but cannot be loaded is deleted so the next call regenerates it.
     */
    private fun secretKey(): SecretKey? {
        cachedKey?.let { return it }
        if (System.currentTimeMillis() < keyRetryAfterMs) return null
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = runCatching { ks.getKey(ALIAS, null) as? SecretKey }.getOrNull()
            val key = if (existing != null) {
                existing
            } else {
                // Either never created, or present but unreadable (Keystore reset,
                // OEM key-storage bug): make sure the stale entry is gone before a
                // fresh one is generated - this is the self-healing step the old
                // EncryptedSharedPreferences store never performed.
                runCatching { ks.deleteEntry(ALIAS) }
                generateKey()
            }
            cachedKey = key
            key
        } catch (t: Throwable) {
            Log.e(TAG, "keystore unavailable (${t.javaClass.simpleName}: ${t.message})")
            cachedKey = null
            keyRetryAfterMs = System.currentTimeMillis() + KEY_RETRY_BACKOFF_MS
            null
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                // Not bound to a screen lock / biometric: the app must be able to
                // read its sessions from a foreground service and after reboot.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + body
    }

    private fun decrypt(key: SecretKey, encoded: String): String {
        val blob = decode(encoded)
        require(blob.size > IV_LENGTH) { "ciphertext too short" }
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val body = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS * 8, iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val TAG = "KeystoreSecretStore"
        private const val PREFS_NAME = "vibeplayer_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "vibeplayer_secret_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 16
        private const val PREFIX = "v1:"
        private const val KEY_RETRY_BACKOFF_MS = 15_000L
    }
}
