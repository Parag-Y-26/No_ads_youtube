package com.ytextapp.ui

import android.content.Intent
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
import androidx.compose.material.icons.filled.FileDownload
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
import com.ytextapp.download.ContainerFormat
import com.ytextapp.download.DownloadRequest
import com.ytextapp.download.VideoDownloadManager
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

    // Download bottom sheet state
    var pendingDownloadRequest by remember { mutableStateOf<DownloadRequest?>(null) }

    // Handle download request from WebView bridge
    fun handleDownloadRequest(request: DownloadRequest) {
        android.util.Log.d("YTExtMainScreen", "handleDownloadRequest called: $request")
        pendingDownloadRequest = request
    }

    // Handle save to device (direct download)
    fun handleSaveClick(format: ContainerFormat, quality: String) {
        pendingDownloadRequest?.let { request ->
            val updatedRequest = request.copy(format = format, quality = quality)
            val downloadManager = VideoDownloadManager.getInstance(context)
            downloadManager.startDirectDownload(updatedRequest, request.url)
        }
        pendingDownloadRequest = null
    }

    // Handle external downloader
    fun handleExternalClick() {
        pendingDownloadRequest?.let { request ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, request.url)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.btn_external_downloader)
            )
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
        pendingDownloadRequest = null
    }

    // Handle progress request from long-press on download button
    fun handleProgressRequest() {
        android.util.Log.d("YTExtMainScreen", "handleProgressRequest called")
        val downloadManager = VideoDownloadManager.getInstance(context)
        // The progress is already shown via notification, but we could also
        // show a toast or open the download sheet if not already open
        if (pendingDownloadRequest != null) {
            // Already showing the sheet, progress is in notification
        } else {
            // Show a toast indicating download is in progress
            android.widget.Toast.makeText(
                context,
                "Download in progress - check notification bar",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

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
                        IconButton(onClick = {
                            val wv = webViewState.value ?: return@IconButton
                            val pageUrl = wv.url ?: return@IconButton
                            val pageTitle = wv.title
                                ?.replace(" - YouTube", "")
                                ?.trim()
                                ?: "Video"
                            val videoIdRegex = Regex("""(?:[?&]v=|shorts/|embed/|youtu\.be/)([a-zA-Z0-9_-]{11})""")
                            val videoId = videoIdRegex.find(pageUrl)?.groupValues?.getOrNull(1) ?: ""
                            handleDownloadRequest(
                                DownloadRequest(
                                    videoId = videoId,
                                    url = pageUrl,
                                    title = pageTitle,
                                    format = ContainerFormat.MP4,
                                    quality = "720p",
                                )
                            )
                        }) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.btn_download),
                            )
                        }
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
                    onDownloadRequested = ::handleDownloadRequest,
                    onProgressRequested = ::handleProgressRequest,
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

        // Download bottom sheet
        pendingDownloadRequest?.let { request ->
            DownloadBottomSheet(
                request = request,
                onDismissRequest = { pendingDownloadRequest = null },
                onSaveClick = ::handleSaveClick,
                onExternalClick = ::handleExternalClick,
            )
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