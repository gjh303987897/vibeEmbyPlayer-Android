package com.vibeplayer.app.data.local.datastore

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.vibeplayer.app.security.KeystoreSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Securely stores access tokens, user ids and opt-in saved passwords for media
 * server accounts (Emby / Jellyfin / WebDAV).
 *
 * Values are persisted through [KeystoreSecretStore]: AES-256-GCM with a
 * non-exportable Android Keystore key, so no token or password is ever written
 * in clear text and nothing sensitive is logged.
 *
 * The store used to be `EncryptedSharedPreferences`. That library is deprecated
 * and fails as a whole unit: when its Keystore master key cannot be used, the
 * store can neither be read nor written again for the rest of the process, and
 * the failure is invisible to the user — a saved session / saved password
 * simply "wasn't there" the next time, so signing in had to be repeated. The
 * current implementation degrades per entry and heals itself instead.
 *
 * Any values still readable in the legacy file are migrated once, so existing
 * installs keep their sessions.
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: KeystoreSecretStore
) {

    private val migrated = AtomicBoolean(false)

    private fun userIdKey(serverId: String) = "${serverId}_user_id"
    private fun tokenKey(serverId: String) = "${serverId}_token"
    private fun passwordKey(serverId: String) = "${serverId}_password"

    fun saveSession(serverId: String, userId: String, accessToken: String) {
        ensureLegacyMigration()
        val ok = store.putString(tokenKey(serverId), accessToken) &&
            store.putString(userIdKey(serverId), userId)
        if (!ok) Log.w(TAG, "session could not be persisted for server (keystore unavailable)")
    }

    fun accessToken(serverId: String): String? {
        ensureLegacyMigration()
        return store.getString(tokenKey(serverId))
    }

    fun userId(serverId: String): String? {
        ensureLegacyMigration()
        return store.getString(userIdKey(serverId))
    }

    fun hasSession(serverId: String): Boolean =
        !accessToken(serverId).isNullOrEmpty() && !userId(serverId).isNullOrEmpty()

    fun clearSession(serverId: String) {
        store.remove(tokenKey(serverId))
        store.remove(userIdKey(serverId))
    }

    /**
     * Stores a plain credential (e.g. an Emby/WebDAV password) securely per server.
     * Returns false when the Keystore is unavailable, so the UI can tell the user
     * that one-tap entry will not work instead of failing silently.
     */
    fun savePassword(serverId: String, password: String): Boolean {
        ensureLegacyMigration()
        val ok = store.putString(passwordKey(serverId), password)
        if (!ok) Log.w(TAG, "password could not be persisted for server (keystore unavailable)")
        return ok
    }

    fun password(serverId: String): String? {
        ensureLegacyMigration()
        return store.getString(passwordKey(serverId))
    }

    fun clearPassword(serverId: String) {
        store.remove(passwordKey(serverId))
    }

    /**
     * One-time, best-effort move of the old `EncryptedSharedPreferences` payload
     * into [KeystoreSecretStore]. Runs only when the legacy file exists and never
     * throws: if the old Keystore master key is broken (the very case that made
     * this store useless) there is nothing to salvage and we just carry on.
     */
    private fun ensureLegacyMigration() {
        if (!migrated.compareAndSet(false, true)) return
        val legacyFile = File(
            context.applicationInfo.dataDir + File.separator + "shared_prefs" +
                File.separator + LEGACY_FILE_NAME + ".xml"
        )
        if (!legacyFile.exists()) return
        runCatching {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            @Suppress("DEPRECATION")
            val legacy = EncryptedSharedPreferences.create(
                masterKey,
                LEGACY_FILE_NAME,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val secretKeys = legacy.all.keys.filter { isSecretKey(it) }
            var copied = 0
            secretKeys.forEach { key ->
                val value = runCatching { legacy.getString(key, null) }.getOrNull()
                if (!value.isNullOrBlank() && store.putStringIfAbsent(key, value)) copied++
            }
            Log.i(TAG, "migrated $copied of ${secretKeys.size} legacy secure entries")
            // Only discard the old file once every entry we saw is safely in the
            // new store; otherwise let a later start try again.
            if (secretKeys.all { store.getString(it) != null }) {
                runCatching { legacyFile.delete() }
            } else {
                migrated.set(false)
            }
        }.onFailure {
            Log.w(TAG, "legacy secure-store migration skipped (${it.javaClass.simpleName})")
            migrated.set(false)
        }
    }

    /** True for the session / password entries this store owns (not library metadata). */
    private fun isSecretKey(key: String): Boolean =
        key.endsWith("_token") || key.endsWith("_user_id") || key.endsWith("_password")

    companion object {
        private const val TAG = "SecureSessionStore"
        private const val LEGACY_FILE_NAME = "vibeplayer_secure_sessions"
    }
}
