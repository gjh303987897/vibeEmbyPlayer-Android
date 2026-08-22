package com.vibeplayer.app.data.local.datastore

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Securely stores access tokens and user ids for media server sessions.
 *
 * Uses Android Keystore-backed EncryptedSharedPreferences. Passwords are never
 * stored anywhere; only the server-issued access token is persisted so auto
 * login can reuse an existing session.
 *
 * The Keystore / EncryptedSharedPreferences stack is a known source of sporadic
 * process-killing crashes on some devices and Android versions (a failure during
 * lazy master-key creation, or on the library's own background writer thread,
 * terminates the whole process). Every operation here is therefore wrapped so a
 * security/provider failure can never crash the app: it simply degrades to
 * "no session persisted", which only means auto-login falls back to asking for
 * the password again.
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                masterKey,
                FILE_NAME,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrNull()
    }

    fun saveSession(serverId: String, userId: String, accessToken: String) {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .putString("${serverId}_user_id", userId)
                .putString("${serverId}_token", accessToken)
                .apply()
        }
    }

    fun accessToken(serverId: String): String? =
        prefs?.let { p ->
            runCatching { p.getString("${serverId}_token", null) }.getOrNull()
        }

    fun userId(serverId: String): String? =
        prefs?.let { p ->
            runCatching { p.getString("${serverId}_user_id", null) }.getOrNull()
        }

    fun hasSession(serverId: String): Boolean =
        !accessToken(serverId).isNullOrEmpty()

    fun clearSession(serverId: String) {
        val p = prefs ?: return
        runCatching {
            p.edit()
                .remove("${serverId}_token")
                .remove("${serverId}_user_id")
                .apply()
        }
    }

    /** Stores a plain credential (e.g. WebDAV password) securely per server. */
    fun savePassword(serverId: String, password: String) {
        val p = prefs ?: return
        runCatching {
            p.edit().putString("${serverId}_password", password).apply()
        }
    }

    fun password(serverId: String): String? =
        prefs?.let { p ->
            runCatching { p.getString("${serverId}_password", null) }.getOrNull()
        }

    fun clearPassword(serverId: String) {
        val p = prefs ?: return
        runCatching {
            p.edit().remove("${serverId}_password").apply()
        }
    }

    companion object {
        private const val FILE_NAME = "vibeplayer_secure_sessions"
    }
}
