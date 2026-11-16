package com.ytextapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ytextapp.R
import com.ytextapp.download.DownloadRequest
import com.ytextapp.download.StreamResolution
import com.ytextapp.download.YouTubeStreamResolver
import com.ytextapp.download.YouTubeStreamResolver.DownloadKind

private val DownloadYellow = Color(0xFFFFD400)

/**
 * The picker opened by the injected download button.
 *
 * Formats are supplied by [YouTubeStreamResolver]; this composable only decides how
 * they are grouped and which one is selected. Nothing here is hardcoded to a video
 * quality, so a video that only has 360p will show only 360p.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBottomSheet(
    request: DownloadRequest,
    resolution: StreamResolution,
    onDismissRequest: () -> Unit,
    onDownloadClick: (YouTubeStreamResolver.DownloadOption) -> Unit,
    onRetry: () -> Unit,
    onExternalClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var selectedId by rememberSaveable(request.videoId) { mutableStateOf<String?>(null) }
    var showMore by rememberSaveable(request.videoId) { mutableStateOf(false) }

    val ready = resolution as? StreamResolution.Ready
    val options = ready?.video?.options.orEmpty()

    // Pick a sensible default once the resolver answers: the user's configured
    // quality when it exists, otherwise the first featured video option.
    LaunchedEffect(ready?.video?.videoId, options) {
        if (options.isEmpty()) return@LaunchedEffect
        if (options.none { it.id == selectedId }) {
            selectedId = defaultOption(options, request.quality)?.id
        }
    }

    val selectedOption = options.firstOrNull { it.id == selectedId }
    val musicOptions = options.filter { it.kind == DownloadKind.AUDIO }
    val featuredVideo = options.filter {
        it.kind == DownloadKind.VIDEO && it.featured
    }
    val moreVideo = options.filter {
        it.kind == DownloadKind.VIDEO && !it.featured
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 18.dp),
        ) {
            // Drag handle, matching the rest of the app's sheets.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(44.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.download_sheet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            VideoHeader(
                request = request,
                thumbnailUrl = ready?.video?.thumbnailUrl
                    ?: "https://i.ytimg.com/vi/${request.videoId}/mqdefault.jpg",
                author = ready?.video?.author.orEmpty(),
            )

            Spacer(modifier = Modifier.height(22.dp))

            when (resolution) {
                is StreamResolution.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 26.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = DownloadYellow,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.download_preparing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is StreamResolution.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                            .padding(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.download_unavailable),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = resolution.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text(stringResource(R.string.download_retry))
                        }
                        TextButton(onClick = onExternalClick) {
                            Text(stringResource(R.string.btn_external_downloader))
                        }
                    }
                }

                is StreamResolution.Ready -> {
                    if (musicOptions.isNotEmpty()) {
                        SectionHeader(
                            icon = Icons.Filled.MusicNote,
                            title = stringResource(R.string.download_section_music),
                        )
                        musicOptions.forEach { option ->
                            DownloadOptionRow(
                                option = option,
                                selected = option.id == selectedId,
                                onClick = { selectedId = option.id },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    if (featuredVideo.isNotEmpty()) {
                        SectionHeader(
                            icon = Icons.Filled.Videocam,
                            title = stringResource(R.string.download_section_video),
                        )
                        featuredVideo.forEach { option ->
                            DownloadOptionRow(
                                option = option,
                                selected = option.id == selectedId,
                                onClick = { selectedId = option.id },
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    if (moreVideo.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showMore = !showMore }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    if (showMore) R.string.download_fewer_formats
                                    else R.string.download_more_formats,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showMore) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        if (showMore) {
                            Spacer(modifier = Modifier.height(4.dp))
                            moreVideo.forEach { option ->
                                DownloadOptionRow(
                                    option = option,
                                    selected = option.id == selectedId,
                                    onClick = { selectedId = option.id },
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = { selectedOption?.let(onDownloadClick) },
                        enabled = selectedOption != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DownloadYellow,
                            contentColor = Color.Black,
                            disabledContainerColor = DownloadYellow.copy(alpha = 0.4f),
                            disabledContentColor = Color.Black.copy(alpha = 0.5f),
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = stringResource(R.string.download_cta),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.download_mux_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )

                    TextButton(
                        onClick = onExternalClick,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text(stringResource(R.string.btn_external_downloader))
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoHeader(
    request: DownloadRequest,
    thumbnailUrl: String,
    author: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(116.dp)
                .height(66.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (author.isBlank()) {
                    stringResource(R.string.download_source_youtube)
                } else {
                    stringResource(R.string.download_source_youtube_author, author)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadOptionRow(
    option: YouTubeStreamResolver.DownloadOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) DownloadYellow else MaterialTheme.colorScheme.outlineVariant
    val background = if (selected) {
        DownloadYellow.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) DownloadYellow else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
                .padding(4.dp)
                .background(
                    color = if (selected) DownloadYellow else Color.Transparent,
                    shape = CircleShape,
                ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = option.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun defaultOption(
    options: List<YouTubeStreamResolver.DownloadOption>,
    preferredQuality: String,
): YouTubeStreamResolver.DownloadOption? {
    val videos = options.filter { it.kind == DownloadKind.VIDEO }
    return videos.firstOrNull { it.quality == preferredQuality && it.featured }
        ?: videos.firstOrNull { it.featured }
        ?: videos.firstOrNull()
        ?: options.firstOrNull()
}
