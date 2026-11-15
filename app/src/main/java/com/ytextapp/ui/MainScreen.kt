package com.ytextapp.ui

import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ytextapp.R
import com.ytextapp.webview.YouTubeWebView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    adBlockEnabled: Boolean,
    onAdBlockChanged: (Boolean) -> Unit,
    sponsorBlockEnabled: Boolean,
    onSponsorBlockChanged: (Boolean) -> Unit,
    backgroundPlayEnabled: Boolean,
    onBackgroundPlayChanged: (Boolean) -> Unit,
    desktopModeEnabled: Boolean,
    onDesktopModeChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val webViewState = remember { mutableStateOf<WebView?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    val sheetState = rememberModalBottomSheetState()

    Box(modifier = Modifier.fillMaxSize()) {
        // Fullscreen video overlay
        if (fullscreenView != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        addView(fullscreenView)
                    }
                },
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Toolbar
                TopAppBar(
                    title = { },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    navigationIcon = {
                        Row {
                            IconButton(onClick = { webViewState.value?.goBack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.btn_back),
                                )
                            }
                            IconButton(onClick = { webViewState.value?.goForward() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(R.string.btn_forward),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { webViewState.value?.reload() }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.btn_reload),
                            )
                        }
                        IconButton(onClick = { onDesktopModeChanged(!desktopModeEnabled) }) {
                            Icon(
                                if (desktopModeEnabled) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                                contentDescription = stringResource(R.string.btn_desktop_toggle),
                            )
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.btn_settings),
                            )
                        }
                    },
                )

                // WebView
                YouTubeWebView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    url = stringResource(R.string.youtube_url),
                    desktopMode = desktopModeEnabled,
                    adBlockEnabled = adBlockEnabled,
                    sponsorBlockEnabled = sponsorBlockEnabled,
                    backgroundPlayEnabled = backgroundPlayEnabled,
                    coroutineScope = coroutineScope,
                    webViewState = webViewState,
                    onFullscreenChanged = { view -> fullscreenView = view },
                )
            }
        }

        // Settings bottom sheet
        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = sheetState,
            ) {
                SettingsSheet(
                    adBlockEnabled = adBlockEnabled,
                    onAdBlockChanged = onAdBlockChanged,
                    sponsorBlockEnabled = sponsorBlockEnabled,
                    onSponsorBlockChanged = onSponsorBlockChanged,
                    backgroundPlayEnabled = backgroundPlayEnabled,
                    onBackgroundPlayChanged = onBackgroundPlayChanged,
                    desktopModeEnabled = desktopModeEnabled,
                    onDesktopModeChanged = onDesktopModeChanged,
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    adBlockEnabled: Boolean,
    onAdBlockChanged: (Boolean) -> Unit,
    sponsorBlockEnabled: Boolean,
    onSponsorBlockChanged: (Boolean) -> Unit,
    backgroundPlayEnabled: Boolean,
    onBackgroundPlayChanged: (Boolean) -> Unit,
    desktopModeEnabled: Boolean,
    onDesktopModeChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))

        SettingsToggle(
            label = stringResource(R.string.toggle_ad_block),
            checked = adBlockEnabled,
            onCheckedChange = onAdBlockChanged,
        )

        SettingsToggle(
            label = stringResource(R.string.toggle_sponsorblock),
            checked = sponsorBlockEnabled,
            onCheckedChange = onSponsorBlockChanged,
        )

        SettingsToggle(
            label = stringResource(R.string.toggle_background_play),
            checked = backgroundPlayEnabled,
            onCheckedChange = onBackgroundPlayChanged,
        )

        SettingsToggle(
            label = stringResource(R.string.toggle_desktop_mode),
            checked = desktopModeEnabled,
            onCheckedChange = onDesktopModeChanged,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
