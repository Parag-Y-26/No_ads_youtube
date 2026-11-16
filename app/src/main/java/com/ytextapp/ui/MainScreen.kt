package com.ytextapp.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isEmpty
import com.ytextapp.R
import com.ytextapp.download.ContainerFormat
import com.ytextapp.download.DownloadProgress
import com.ytextapp.download.DownloadRequest
import com.ytextapp.download.StreamResolution
import com.ytextapp.download.VideoDownloadManager
import com.ytextapp.download.YouTubeStreamResolver
import com.ytextapp.webview.WebViewNavigationState
import com.ytextapp.webview.YouTubeWebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

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
    var navigationState by remember { mutableStateOf(WebViewNavigationState()) }
    var safeAreaSize by remember { mutableStateOf(IntSize.Zero) }
    // Settings has nine controls; a half-height sheet would cut the last toggle off.
    // Open expanded so every option is visible without scrolling.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Download bottom sheet state
    var pendingDownloadRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    var streamResolution by remember { mutableStateOf<StreamResolution>(StreamResolution.Loading) }
    var resolutionJob by remember { mutableStateOf<Job?>(null) }
    val downloadManager = remember { VideoDownloadManager.getInstance(context) }
    var activeDownloadVideoId by remember { mutableStateOf<String?>(null) }
    var activeDownloadTaskId by remember { mutableStateOf<Long?>(null) }

    /** Sends one progress update to the pill under the injected button. */
    fun pushProgressToPage(payload: JSONObject) {
        webViewState.value?.evaluateJavascript(
            "window.__ytExtDownloadProgress && window.__ytExtDownloadProgress($payload);",
            null,
        )
    }

    fun progressPayload(progress: DownloadProgress, videoId: String): JSONObject {
        val state: String
        val percent: Int
        val text: String
        var detail = ""
        when (progress) {
            is DownloadProgress.Downloading -> {
                state = "downloading"
                // The manager already reports one overall percentage, including the
                // switch from downloading to muxing. Do not recompute it from bytes
                // here: during a muxed download that would jump back to 0.
                percent = progress.percent
                if (progress.speedBps > 0) {
                    detail = " · ${YouTubeStreamResolver.formatBytes(progress.speedBps)}/s"
                }
                text = if (percent >= 0) "$percent%$detail" else "Downloading…"
            }
            is DownloadProgress.Muxing -> {
                state = "processing"
                percent = progress.percent
                text = "Finishing… $percent%"
            }
            is DownloadProgress.Completed -> {
                state = "complete"
                percent = 100
                text = "Saved"
            }
            is DownloadProgress.Failed -> {
                state = "error"
                percent = 100
                text = "Failed"
            }
            is DownloadProgress.Cancelled -> {
                state = "error"
                percent = 100
                text = "Cancelled"
            }
        }
        return JSONObject().apply {
            put("videoId", videoId)
            put("downloadId", progress.id)
            put("state", state)
            put("percent", percent)
            put("text", text)
            if (detail.isNotEmpty()) put("detail", detail)
        }
    }

    /** Resolves real media URLs for [request] without blocking the UI. */
    fun resolveStreams(request: DownloadRequest) {
        resolutionJob?.cancel()
        if (request.videoId.isBlank()) {
            streamResolution = StreamResolution.Error("Open a video first.")
            return
        }
        streamResolution = StreamResolution.Loading
        resolutionJob = coroutineScope.launch {
            streamResolution = try {
                val resolved = YouTubeStreamResolver.resolve(request.videoId, request.title)
                android.util.Log.d(
                    "YTExtMainScreen",
                    "Resolved ${resolved.options.size} options for ${request.videoId}",
                )
                StreamResolution.Ready(resolved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("YTExtMainScreen", "Resolution failed for ${request.videoId}", e)
                StreamResolution.Error(e.message ?: "Could not load download formats")
            }
        }
    }

    // Handle download request from WebView bridge
    fun handleDownloadRequest(request: DownloadRequest) {
        android.util.Log.d("YTExtMainScreen", "handleDownloadRequest called: $request")
        pendingDownloadRequest = request
        resolveStreams(request)
    }

    /** Builds a download request for the page that is on screen right now. */
    fun requestCurrentDownload() {
        val wv = webViewState.value ?: return
        val pageUrl = wv.url ?: return
        val pageTitle = wv.title
            ?.replace(" - YouTube", "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
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
    }

    // Save the option picked in the sheet. A video-only stream is paired with the
    // best AAC audio stream and muxed by the download manager.
    fun handleSaveClick(option: YouTubeStreamResolver.DownloadOption) {
        val request = pendingDownloadRequest ?: return

        // Only one job may feed the page pill at a time. Without this guard a second
        // sheet/tap can start another download whose percentages interleave with the
        // first one, which looks like the bar jumping forwards and backwards.
        if (activeDownloadTaskId != null) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.download_in_progress),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }

        android.util.Log.d(
            "YTExtMainScreen",
            "handleSaveClick: id=${option.id} title=${option.title} quality=${option.quality} mux=${option.needsMux}",
        )
        val updatedRequest = request.copy(
            format = option.format,
            quality = option.quality,
            mediaUserAgent = option.mediaUserAgent,
        )

        val taskId = downloadManager.newDownloadId()
        activeDownloadVideoId = request.videoId
        activeDownloadTaskId = taskId
        pushProgressToPage(
            JSONObject().apply {
                put("videoId", request.videoId)
                put("downloadId", taskId)
                put("state", "downloading")
                put("percent", 0)
                put("text", "Starting…")
                // Explicitly starts a new 0–100 run. The page keeps its own
                // high-water mark, so a re-render or a stale tick cannot move it back.
                put("reset", true)
            }
        )

        val started = try {
            val audioUrl = option.audioUrl
            if (option.needsMux && audioUrl != null) {
                downloadManager.startSeparateStreamsDownload(
                    request = updatedRequest,
                    videoUrl = option.url,
                    audioUrl = audioUrl,
                    videoSizeBytes = option.sizeBytes,
                    audioSizeBytes = option.audioSizeBytes,
                    downloadId = taskId,
                )
            } else {
                downloadManager.startDirectDownload(updatedRequest, option.url, downloadId = taskId)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("YTExtMainScreen", "Could not start download", e)
            false
        }

        if (!started) {
            activeDownloadTaskId = null
            activeDownloadVideoId = null
            pushProgressToPage(
                JSONObject().apply {
                    put("videoId", request.videoId)
                    put("downloadId", taskId)
                    put("state", "error")
                    put("percent", 100)
                    put("text", context.getString(R.string.download_start_failed))
                }
            )
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.download_start_failed),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
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
        val current = downloadManager.progress.value
        val videoId = activeDownloadVideoId
        val activeId = activeDownloadTaskId
        val isActive = current != null &&
            current.id == activeId &&
            (current is DownloadProgress.Downloading || current is DownloadProgress.Muxing)
        if (current != null && videoId != null && isActive) {
            pushProgressToPage(progressPayload(current, videoId))
        } else {
            android.widget.Toast.makeText(
                context,
                "No active download",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Forward download progress into the page so it can render under the button.
    // Terminal states are always delivered; the download task throttles the rest.
    // Progress from any other task is ignored so two downloads can never interleave
    // their percentages on the same pill.
    LaunchedEffect(downloadManager) {
        downloadManager.progress.collect { progress ->
            if (progress == null) return@collect
            val videoId = activeDownloadVideoId ?: return@collect
            val taskId = activeDownloadTaskId ?: return@collect
            if (progress.id != taskId) return@collect

            pushProgressToPage(progressPayload(progress, videoId))

            if (
                progress is DownloadProgress.Completed ||
                progress is DownloadProgress.Failed ||
                progress is DownloadProgress.Cancelled
            ) {
                activeDownloadTaskId = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The WebView always stays in composition. The old layout removed it while a
        // fullscreen video was showing, which tore down the renderer.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            YouTubeWebView(
                modifier = Modifier.fillMaxSize(),
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
                onNavigationStateChanged = { state -> navigationState = state },
            )
        }

        // Only the floating button lives above the page. The container itself has no
        // pointer input, so every touch outside the button goes straight to the WebView.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .onSizeChanged { safeAreaSize = it }
        ) {
            if (fullscreenView == null) {
                FloatingSettingsButton(
                    containerSize = safeAreaSize,
                    onClick = { showSettings = true },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
        }

        // Drawn last so a fullscreen video covers the whole window.
        fullscreenView?.let { view ->
            FullscreenVideoOverlay(view)
        }
    }

    // Settings bottom sheet
    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = sheetState,
        ) {
            SettingsSheet(
                navigationState = navigationState,
                onBack = { webViewState.value?.goBack() },
                onForward = { webViewState.value?.goForward() },
                onReload = { webViewState.value?.reload() },
                onDownload = ::requestCurrentDownload,
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
            resolution = streamResolution,
            onDismissRequest = {
                pendingDownloadRequest = null
                resolutionJob?.cancel()
            },
            onDownloadClick = ::handleSaveClick,
            onRetry = { resolveStreams(request) },
            onExternalClick = ::handleExternalClick,
        )
    }
}

/**
 * A small, translucent button that overlays the page instead of shrinking it.
 *
 * It can be dragged anywhere along the edge (the position is kept across rotation)
 * so it never has to sit on top of the Shorts action rail or the player controls.
 */
@Composable
private fun FloatingSettingsButton(
    containerSize: IntSize,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var offsetX by rememberSaveable { mutableStateOf(0f) }
    var offsetY by rememberSaveable { mutableStateOf(0f) }
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    val edgeMarginPx = with(LocalDensity.current) { 8.dp.toPx() }

    // Re-clamp after rotation or a window resize, otherwise a button that was
    // dragged to the bottom could end up outside the new window.
    LaunchedEffect(containerSize, buttonSize) {
        if (containerSize == IntSize.Zero || buttonSize == IntSize.Zero) return@LaunchedEffect
        val maxX = (containerSize.width - buttonSize.width - edgeMarginPx * 2f).coerceAtLeast(0f)
        val maxY = ((containerSize.height - buttonSize.height) / 2f - edgeMarginPx).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(0f, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    Surface(
        modifier = modifier
            .padding(start = 4.dp)
            .size(44.dp)
            .onSizeChanged { buttonSize = it }
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(containerSize, buttonSize, edgeMarginPx) {
                detectDragGestures { change, dragAmount ->
                    change.consume()

                    val maxX = (containerSize.width - buttonSize.width - edgeMarginPx * 2f)
                        .coerceAtLeast(0f)
                    val maxY = ((containerSize.height - buttonSize.height) / 2f - edgeMarginPx)
                        .coerceAtLeast(0f)

                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                    offsetY = (offsetY + dragAmount.y).coerceIn(-maxY, maxY)
                }
            }
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.btn_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Hosts YouTube's fullscreen custom view without removing the WebView from the
 * composition. Detaching the child on dispose lets the player reuse the same view
 * the next time fullscreen is entered.
 */
@Composable
private fun FullscreenVideoOverlay(view: View) {
    DisposableEffect(view) {
        onDispose {
            (view.parent as? ViewGroup)?.removeView(view)
        }
    }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        factory = { context ->
            FrameLayout(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { container ->
            if (container.isEmpty() || container.getChildAt(0) !== view) {
                container.removeAllViews()
                (view.parent as? ViewGroup)?.removeView(view)
                container.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
    )
}

@Composable
private fun SettingsSheet(
    navigationState: WebViewNavigationState,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onDownload: () -> Unit,
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
            .verticalScroll(rememberScrollState())
            // The sheet draws behind the gesture/navigation bar. Without this the
            // last toggle ends up half under the system bar and cannot be tapped
            // reliably when the content is scrolled to the bottom.
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = stringResource(R.string.settings_quick_controls),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickAction(
                label = stringResource(R.string.btn_back),
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                enabled = navigationState.canGoBack,
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                label = stringResource(R.string.btn_forward),
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = navigationState.canGoForward,
                onClick = onForward,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                label = stringResource(R.string.btn_reload),
                icon = Icons.Filled.Refresh,
                onClick = onReload,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                label = stringResource(R.string.btn_download),
                icon = Icons.Filled.FileDownload,
                onClick = onDownload,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                label = stringResource(R.string.action_desktop),
                icon = if (desktopModeEnabled) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                onClick = { onDesktopModeChanged(!desktopModeEnabled) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Spacer(modifier = Modifier.height(6.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(21.dp),
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
        )
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
