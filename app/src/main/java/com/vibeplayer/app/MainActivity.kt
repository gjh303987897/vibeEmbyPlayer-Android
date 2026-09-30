package com.vibeplayer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.vibeplayer.app.security.PrivacyManager
import com.vibeplayer.app.ui.navigation.VibePlayerNavHost
import com.vibeplayer.app.ui.settings.SettingsViewModel
import com.vibeplayer.app.ui.theme.VibePlayerTheme
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var privacyManager: PrivacyManager
    private lateinit var privacyBiometricPrompt: BiometricPrompt
    private var privacyBiometricRequested = false

    // Apply the user-selected language before any view / Compose root is created.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocaleIfNeeded(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        privacyBiometricRequested = savedInstanceState?.getBoolean(STATE_PRIVACY_BIOMETRIC_REQUESTED) ?: false
        privacyBiometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (privacyBiometricRequested) {
                        privacyBiometricRequested = false
                        privacyManager.openPrivacyAfterBiometric()
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    privacyBiometricRequested = false
                }
            }
        )
        setContent {
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            val settings by settingsViewModel.uiState.collectAsState()
            // On Android 13+ the app needs the notification permission to show
            // playback / transfer notifications, so request it once at launch.
            val requestNotifications =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val darkTheme = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            VibePlayerTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VibePlayerNavHost(pageTransitions = settings.pageTransitions, privacyManager = privacyManager)
                }
            }
        }
        lifecycleScope.launch {
            privacyManager.privacyMode.collect { unlocked ->
                if (unlocked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            privacyBiometricRequested = false
            privacyBiometricPrompt.cancelAuthentication()
            privacyManager.exitPrivacyMode()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PRIVACY_BIOMETRIC_REQUESTED, privacyBiometricRequested)
        super.onSaveInstanceState(outState)
    }

    fun canUsePrivacyBiometric(): Boolean = privacyManager.isPinConfigured() &&
        !privacyManager.privacyMode.value &&
        BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS

    fun requestPrivacyBiometricUnlock() {
        if (!canUsePrivacyBiometric()) return
        privacyBiometricRequested = true
        privacyBiometricPrompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.settings_biometric_title))
                .setSubtitle(getString(R.string.settings_biometric_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.settings_use_pin))
                .build()
        )
    }

    private companion object {
        const val STATE_PRIVACY_BIOMETRIC_REQUESTED = "privacy_biometric_requested"
    }
}
