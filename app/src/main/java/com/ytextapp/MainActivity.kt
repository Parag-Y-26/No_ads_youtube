package com.ytextapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.ytextapp.data.PreferencesManager
import com.ytextapp.service.BackgroundPlaybackService
import com.ytextapp.ui.MainScreen
import com.ytextapp.ui.theme.YTExtTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        preferencesManager = PreferencesManager(applicationContext)

        setContent {
            val coroutineScope = rememberCoroutineScope()

            // targetSdk 35: on API 33+ the foreground-service notification is suppressed
            // until POST_NOTIFICATIONS is granted, so the switch must not turn on before
            // the user has allowed it.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                coroutineScope.launch {
                    // Keep the switch in sync with what actually happened, otherwise the
                    // UI would claim background playback is on while nothing is running.
                    // The LaunchedEffect below starts the service once this value lands.
                    preferencesManager.setBackgroundPlayEnabled(granted)

                    if (!granted) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.notification_permission_denied,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }

            val adBlockEnabled by preferencesManager.adBlockEnabled.collectAsState(initial = true)
            val sponsorBlockEnabled by preferencesManager.sponsorBlockEnabled.collectAsState(initial = true)
            val backgroundPlayEnabled by preferencesManager.backgroundPlayEnabled.collectAsState(initial = false)
            val desktopModeEnabled by preferencesManager.desktopModeEnabled.collectAsState(initial = false)

            // The switch is persisted, so a cold start with it already on has to bring the
            // service back — otherwise the UI would claim background playback while no
            // notification, wake lock or MediaSession exists. It is also the single place
            // the service is started, so the toggle path cannot start it twice.
            LaunchedEffect(backgroundPlayEnabled) {
                if (!backgroundPlayEnabled) return@LaunchedEffect

                if (needsNotificationPermission()) {
                    // Revoked outside the app: do not advertise a feature that cannot run.
                    preferencesManager.setBackgroundPlayEnabled(false)
                    return@LaunchedEffect
                }

                BackgroundPlaybackService.start(this@MainActivity)
            }

            YTExtTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        adBlockEnabled = adBlockEnabled,
                        onAdBlockChanged = { enabled ->
                            coroutineScope.launch { preferencesManager.setAdBlockEnabled(enabled) }
                        },
                        sponsorBlockEnabled = sponsorBlockEnabled,
                        onSponsorBlockChanged = { enabled ->
                            coroutineScope.launch { preferencesManager.setSponsorBlockEnabled(enabled) }
                        },
                        backgroundPlayEnabled = backgroundPlayEnabled,
                        onBackgroundPlayChanged = { enabled ->
                            coroutineScope.launch {
                                when {
                                    !enabled -> {
                                        preferencesManager.setBackgroundPlayEnabled(false)
                                        BackgroundPlaybackService.stop(this@MainActivity)
                                    }

                                    needsNotificationPermission() -> {
                                        // Persisted by the launcher callback once the
                                        // user answers the system prompt.
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    }

                                    else -> {
                                        // The LaunchedEffect above performs the actual
                                        // start once the new value reaches the UI state.
                                        preferencesManager.setBackgroundPlayEnabled(true)
                                    }
                                }
                            }
                        },
                        desktopModeEnabled = desktopModeEnabled,
                        onDesktopModeChanged = { enabled ->
                            coroutineScope.launch { preferencesManager.setDesktopModeEnabled(enabled) }
                        },
                    )
                }
            }
        }
    }

    /** POST_NOTIFICATIONS only exists from API 33, and only matters while it is not granted. */
    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    }

    override fun onStop() {
        super.onStop()
        // Background playback service is managed by the toggle — nothing extra needed
    }
}
