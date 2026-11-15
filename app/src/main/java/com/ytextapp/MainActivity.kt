package com.ytextapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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

            val adBlockEnabled by preferencesManager.adBlockEnabled.collectAsState(initial = true)
            val sponsorBlockEnabled by preferencesManager.sponsorBlockEnabled.collectAsState(initial = true)
            val backgroundPlayEnabled by preferencesManager.backgroundPlayEnabled.collectAsState(initial = false)
            val desktopModeEnabled by preferencesManager.desktopModeEnabled.collectAsState(initial = false)

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
                                preferencesManager.setBackgroundPlayEnabled(enabled)
                                if (enabled) {
                                    BackgroundPlaybackService.start(this@MainActivity)
                                } else {
                                    BackgroundPlaybackService.stop(this@MainActivity)
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

    override fun onStop() {
        super.onStop()
        // Background playback service is managed by the toggle — nothing extra needed
    }
}
