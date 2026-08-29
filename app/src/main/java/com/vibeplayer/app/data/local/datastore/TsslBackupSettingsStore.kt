package com.vibeplayer.app.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vibeplayer.app.security.KeystoreSecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Non-sensitive TSSL remote-backup settings persisted across app launches. */
data class TsslBackupSettings(
    val target: String = "none",
    val webDavServiceId: String = "",
    val webDavPath: String = "vibePlayerQT/tssl",
    val s3Endpoint: String = "",
    val s3Bucket: String = "",
    val s3Region: String = "us-east-1",
    val s3Prefix: String = "vibePlayerQT/tssl",
    val s3AccessKey: String = "",
    val trustSelfSignedCertificate: Boolean = false
)

/**
 * Stores TSSL backup destinations like the desktop settings page. S3 secret
 * material is kept in the Keystore-backed secret store and is never placed in
 * DataStore or exposed through the settings flow.
 */
@Singleton
class TsslBackupSettingsStore @Inject constructor(
    @ApplicationContext context: android.content.Context,
    private val secrets: KeystoreSecretStore
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = {
            File(context.applicationContext.filesDir, "datastore/tssl-backup.preferences_pb")
        }
    )

    private object Keys {
        val TARGET = stringPreferencesKey("target")
        val WEBDAV_SERVICE_ID = stringPreferencesKey("webdav_service_id")
        val WEBDAV_PATH = stringPreferencesKey("webdav_path")
        val S3_ENDPOINT = stringPreferencesKey("s3_endpoint")
        val S3_BUCKET = stringPreferencesKey("s3_bucket")
        val S3_REGION = stringPreferencesKey("s3_region")
        val S3_PREFIX = stringPreferencesKey("s3_prefix")
        val S3_ACCESS_KEY = stringPreferencesKey("s3_access_key")
        val TRUST_SELF_SIGNED = booleanPreferencesKey("trust_self_signed")
    }

    val settings: Flow<TsslBackupSettings> = dataStore.data.map { p ->
        TsslBackupSettings(
            target = p[Keys.TARGET] ?: "none",
            webDavServiceId = p[Keys.WEBDAV_SERVICE_ID].orEmpty(),
            webDavPath = p[Keys.WEBDAV_PATH] ?: "vibePlayerQT/tssl",
            s3Endpoint = p[Keys.S3_ENDPOINT].orEmpty(),
            s3Bucket = p[Keys.S3_BUCKET].orEmpty(),
            s3Region = p[Keys.S3_REGION] ?: "us-east-1",
            s3Prefix = p[Keys.S3_PREFIX] ?: "vibePlayerQT/tssl",
            s3AccessKey = p[Keys.S3_ACCESS_KEY].orEmpty(),
            trustSelfSignedCertificate = p[Keys.TRUST_SELF_SIGNED] ?: false
        )
    }

    suspend fun update(transform: (TsslBackupSettings) -> TsslBackupSettings) {
        dataStore.edit { p ->
            val current = TsslBackupSettings(
                target = p[Keys.TARGET] ?: "none",
                webDavServiceId = p[Keys.WEBDAV_SERVICE_ID].orEmpty(),
                webDavPath = p[Keys.WEBDAV_PATH] ?: "vibePlayerQT/tssl",
                s3Endpoint = p[Keys.S3_ENDPOINT].orEmpty(),
                s3Bucket = p[Keys.S3_BUCKET].orEmpty(),
                s3Region = p[Keys.S3_REGION] ?: "us-east-1",
                s3Prefix = p[Keys.S3_PREFIX] ?: "vibePlayerQT/tssl",
                s3AccessKey = p[Keys.S3_ACCESS_KEY].orEmpty(),
                trustSelfSignedCertificate = p[Keys.TRUST_SELF_SIGNED] ?: false
            )
            val next = transform(current)
            p[Keys.TARGET] = next.target
            p[Keys.WEBDAV_SERVICE_ID] = next.webDavServiceId
            p[Keys.WEBDAV_PATH] = next.webDavPath
            p[Keys.S3_ENDPOINT] = next.s3Endpoint
            p[Keys.S3_BUCKET] = next.s3Bucket
            p[Keys.S3_REGION] = next.s3Region
            p[Keys.S3_PREFIX] = next.s3Prefix
            p[Keys.S3_ACCESS_KEY] = next.s3AccessKey
            p[Keys.TRUST_SELF_SIGNED] = next.trustSelfSignedCertificate
        }
    }

    fun saveS3Secret(secret: String): Boolean {
        val value = secret.trim()
        return if (value.isEmpty()) {
            secrets.remove(S3_SECRET_KEY)
            true
        } else {
            secrets.putString(S3_SECRET_KEY, value)
        }
    }

    fun s3Secret(): String? = secrets.getString(S3_SECRET_KEY)

    fun hasS3Secret(): Boolean = !s3Secret().isNullOrEmpty()

    companion object {
        private const val S3_SECRET_KEY = "tssl_backup_s3_secret"
    }
}
