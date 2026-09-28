package com.vibeplayer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.hilt.navigation.compose.hiltViewModel
import com.vibeplayer.app.ui.navigation.VibePlayerNavHost
import com.vibeplayer.app.ui.settings.SettingsViewModel
import com.vibeplayer.app.ui.theme.VibePlayerTheme
import com.vibeplayer.app.util.LocaleHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.vibeplayer.app.security.PrivacyManager
import android.view.WindowManager

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var privacyManager: PrivacyManager

    // Apply the user-selected language before any view / Compose root is created.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.applyLocaleIfNeeded(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
        if (isFinishing) privacyManager.exitPrivacyMode()
        super.onDestroy()
    }
}
