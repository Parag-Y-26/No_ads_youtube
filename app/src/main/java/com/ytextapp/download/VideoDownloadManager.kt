package com.ytextapp.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Manages video downloads with a foreground service and progress notification.
 */
class VideoDownloadManager internal constructor(
    internal val context: Context,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    internal val CHANNEL_ID = "yt_ext_downloads"
    private val NOTIFICATION_ID_BASE = 1000

    // Progress stream - using tryEmit for non-suspend calls
    private val _progress = MutableStateFlow<DownloadProgress?>(null)
    val progress = _progress.asStateFlow()

    // Active downloads for cancellation
    private val activeDownloads = mutableMapOf<Long, DownloadTask>()
    private val downloadIdCounter = AtomicLong(System.currentTimeMillis())

    init {
        createNotificationChannel()
    }

    companion object {
        @Suppress("UNUSED_PARAMETER")
        private var INSTANCE: VideoDownloadManager? = null

        fun getInstance(context: Context): VideoDownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoDownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Reserves an id before the task starts. The caller can put the id on screen and
     * arm its progress filter first, so even a download that fails immediately cannot
     * emit a terminal event into an uninitialised UI.
     */
    fun newDownloadId(): Long = downloadIdCounter.incrementAndGet()

    fun startDirectDownload(
        request: DownloadRequest,
        directUrl: String,
        downloadId: Long = newDownloadId(),
    ): Long {
        Log.d(
            "YTExtDownloads",
            "startDirectDownload id=$downloadId quality=${request.quality} host=${runCatching { URL(directUrl).host }.getOrNull()}",
        )
        val task = DirectDownloadTask(
            id = downloadId,
            request = request,
            directUrl = directUrl,
            manager = this,
        )
        activeDownloads[downloadId] = task
        scope.launch { task.run() }
        return downloadId
    }

    fun startSeparateStreamsDownload(
        request: DownloadRequest,
        videoUrl: String,
        audioUrl: String,
        videoSizeBytes: Long = 0L,
        audioSizeBytes: Long = 0L,
        downloadId: Long = newDownloadId(),
    ): Long {
        Log.d(
            "YTExtDownloads",
            "startSeparateStreamsDownload id=$downloadId quality=${request.quality} video=${videoSizeBytes}B audio=${audioSizeBytes}B",
        )
        val task = SeparateStreamsDownloadTask(
            id = downloadId,
            request = request,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            expectedVideoBytes = videoSizeBytes,
            expectedAudioBytes = audioSizeBytes,
            manager = this,
        )
        activeDownloads[downloadId] = task
        scope.launch { task.run() }
        return downloadId
    }

    fun cancel(downloadId: Long) {
        activeDownloads.remove(downloadId)?.cancel()
    }

    fun cancelAll() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
    }

    internal fun updateProgress(progress: DownloadProgress) {
        _progress.tryEmit(progress)
    }

    internal fun removeTask(id: Long) {
        // Keep the last terminal progress value in the flow. Emitting `null` here
        // would race with Completed/Failed (StateFlow conflates values), so the
        // page pill could miss the final state entirely. A new download overwrites
        // this value with its first Downloading event.
        activeDownloads.remove(id)
    }

    internal fun getNotificationId(downloadId: Long): Int = (NOTIFICATION_ID_BASE + (downloadId % 1000)).toInt()

    internal fun getChannelId(): String = CHANNEL_ID

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(com.ytextapp.R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(com.ytextapp.R.string.download_notification_channel_desc)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}

/**
 * Represents the current state of a download.
 *
 * [Downloading.percent] and [Muxing.percent] are both the overall percentage of the
 * whole job, never a percentage local to one phase. That keeps the page pill and the
 * notification on a single 0–100 scale even when a video and audio stream are fetched
 * separately and then muxed.
 */
sealed class DownloadProgress(
    open val id: Long,
    open val title: String,
) {
    data class Downloading(
        override val id: Long,
        override val title: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBps: Long,
        val percent: Int,
    ) : DownloadProgress(id, title)

    data class Muxing(
        override val id: Long,
        override val title: String,
        val percent: Int,
    ) : DownloadProgress(id, title)

    data class Completed(
        override val id: Long,
        override val title: String,
        val uri: Uri,
    ) : DownloadProgress(id, title)

    data class Failed(
        override val id: Long,
        override val title: String,
        val error: String,
    ) : DownloadProgress(id, title)

    data class Cancelled(
        override val id: Long,
        override val title: String,
    ) : DownloadProgress(id, title)
}

/**
 * Abstract download task.
 */
abstract class DownloadTask(
    val id: Long,
    val request: DownloadRequest,
    val manager: VideoDownloadManager,
) {
    private val _cancelled = AtomicBoolean(false)
    private val bytesDownloaded = AtomicLong(0)
    private val startTime = System.currentTimeMillis()
    private var lastProgressAt = 0L

    /**
     * Highest percentage published by this task. Muxing used to restart at 0 after
     * the streams had downloaded, which made the pill jump backwards. Every phase now
     * feeds through the same high-water mark.
     */
    private var lastPercent = -1

    fun cancel() {
        _cancelled.set(true)
    }

    protected val isCancelled: Boolean
        get() = _cancelled.get()

    protected fun checkCancelled() {
        if (isCancelled) throw CancellationException("Download $id cancelled")
    }

    protected fun updateProgress(
        downloaded: Long,
        total: Long,
        speedBytes: Long = downloaded,
        percent: Int? = null,
    ) {
        bytesDownloaded.set(speedBytes)
        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1) / 1000f
        val speed = if (elapsed > 0) (speedBytes / elapsed).toLong() else 0L
        val computedPercent = percent ?: if (total > 0) {
            ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        val stablePercent = if (computedPercent >= 0) {
            maxOf(computedPercent, lastPercent).coerceIn(0, 100)
        } else {
            computedPercent
        }
        if (stablePercent >= 0) lastPercent = stablePercent

        // The network loop reports every 64 KB. Four updates per second are plenty
        // for the page pill and the notification, and stop the WebView JS bridge
        // from being flooded on fast connections.
        val now = System.currentTimeMillis()
        if (now - lastProgressAt < 250L) return
        lastProgressAt = now

        manager.updateProgress(DownloadProgress.Downloading(
            id = id,
            title = request.title,
            bytesDownloaded = downloaded,
            totalBytes = total,
            speedBps = speed,
            percent = stablePercent,
        ))

        val notification = buildNotification(downloaded, total, speed, stablePercent)
        manager.notificationManager.notify(manager.getNotificationId(id), notification)
    }

    /** Publishes muxing progress on the same 0–100 scale as the downloads. */
    protected fun updateMuxingProgress(phasePercent: Int) {
        // Downloading both streams consumes the first 90%, muxing and copying the
        // final 10%. A mux percentage of 100 is deliberately held at 99: the task is
        // only really finished once the file has been validated and published.
        val overall = (90 + (phasePercent.coerceIn(0, 100) * 10 / 100)).coerceIn(90, 99)
        val stablePercent = maxOf(overall, lastPercent).coerceIn(90, 99)
        lastPercent = stablePercent

        manager.updateProgress(DownloadProgress.Muxing(
            id = id,
            title = request.title,
            percent = stablePercent,
        ))

        val notification = buildMuxingNotification(stablePercent)
        manager.notificationManager.notify(manager.getNotificationId(id), notification)
    }

    protected fun buildNotification(
        downloaded: Long,
        total: Long,
        speedBps: Long,
        percent: Int,
    ): Notification {
        val speedStr = formatSpeed(speedBps)
        val downloadedStr = formatBytes(downloaded)
        val totalStr = if (total > 0) formatBytes(total) else "?"
        val progressText = if (percent >= 0) {
            "$percent% · $downloadedStr / $totalStr · $speedStr"
        } else {
            "$downloadedStr / $totalStr · $speedStr"
        }

        val contentIntent = PendingIntent.getActivity(
            manager.context,
            0,
            Intent(manager.context, com.ytextapp.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(manager.context, manager.CHANNEL_ID)
            .setSmallIcon(com.ytextapp.R.drawable.ic_notification_play)
            .setContentTitle(request.title)
            .setContentText(progressText)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .build()
    }

    protected fun buildMuxingNotification(percent: Int): Notification {
        val contentIntent = PendingIntent.getActivity(
            manager.context,
            0,
            Intent(manager.context, com.ytextapp.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(manager.context, manager.CHANNEL_ID)
            .setSmallIcon(com.ytextapp.R.drawable.ic_notification_play)
            .setContentTitle(request.title)
            .setContentText("Finishing… $percent%")
            .setProgress(100, percent, false)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bps: Long): String {
        return "${formatBytes(bps)}/s"
    }

    suspend fun run() {
        try {
            val uri = doRun()
            manager.updateProgress(DownloadProgress.Completed(id, request.title, uri))
        } catch (e: CancellationException) {
            manager.updateProgress(DownloadProgress.Cancelled(id, request.title))
        } catch (e: Exception) {
            Log.e("YTExtDownloads", "Download $id failed", e)
            manager.updateProgress(DownloadProgress.Failed(id, request.title, e.message ?: e.javaClass.simpleName))
        } finally {
            manager.notificationManager.cancel(manager.getNotificationId(id))
            manager.removeTask(id)
        }
    }

    protected abstract suspend fun doRun(): Uri

    /**
     * Rejects the HTML/JSON error pages YouTube sometimes returns with HTTP 200.
     * Writing one of those to `Movies` is how a download ends up looking like a video
     * but failing in every player.
     */
    protected fun verifyMediaContentType(connection: HttpURLConnection) {
        val contentType = connection.contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?: return
        if (
            contentType.startsWith("text/") ||
            contentType == "application/json" ||
            contentType == "application/xml"
        ) {
            throw IOException("Media server returned $contentType instead of media")
        }
    }

    /** Throws when the socket ended before the announced number of bytes arrived. */
    protected fun verifyDownloadLength(totalRead: Long, expectedLength: Long) {
        if (totalRead <= 0L) throw IOException("Media server returned an empty file")
        if (expectedLength > 0L && totalRead != expectedLength) {
            throw IOException("Incomplete download: received $totalRead of $expectedLength bytes")
        }
    }

    /**
     * Confirms that a finished file can at least be opened as media and contains the
     * tracks we are about to advertise. This catches truncated MP4s whose `moov` atom
     * survived, as well as HTML files saved with an .mp4 extension.
     */
    protected fun validateMediaFile(
        file: File,
        requireVideo: Boolean,
        requireAudio: Boolean,
    ) {
        if (!file.isFile || file.length() <= 0L) {
            throw IOException("Downloaded file is empty")
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            validateTracks(extractor, requireVideo, requireAudio)
        } finally {
            extractor.release()
        }
    }

    private fun validateTracks(
        extractor: MediaExtractor,
        requireVideo: Boolean,
        requireAudio: Boolean,
    ) {
        var hasVideo = false
        var hasAudio = false
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("video/")) hasVideo = true
            if (mime.startsWith("audio/")) hasAudio = true
        }

        if (requireVideo && !hasVideo) throw IOException("Downloaded file has no video track")
        if (requireAudio && !hasAudio) throw IOException("Downloaded file has no audio track")
        if (!hasVideo && !hasAudio) throw IOException("Downloaded file has no playable tracks")
    }

    /**
     * Downloads [urlString] to [destination], resuming with an HTTP Range request when
     * the connection drops. YouTube's CDN occasionally resets long transfers; without
     * this a 30 MB video would start over (or, before the integrity checks, be saved
     * truncated). The callback always receives the absolute byte count for the file.
     */
    protected suspend fun downloadToFile(
        urlString: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        if (destination.exists()) destination.delete()
        var attempts = 0
        var totalSize = 0L

        while (true) {
            checkCancelled()
            val offset = if (destination.isFile) destination.length() else 0L
            if (totalSize > 0 && offset >= totalSize) return offset
            if (totalSize > 0 && offset > totalSize) {
                destination.delete()
            }

            try {
                val attemptTotal = downloadAttempt(
                    urlString = urlString,
                    destination = destination,
                    initialOffset = if (destination.isFile) destination.length() else 0L,
                    knownTotal = totalSize,
                    onProgress = onProgress,
                )
                if (attemptTotal > 0) totalSize = attemptTotal

                val length = destination.length()
                if (totalSize > 0 && length < totalSize) {
                    throw IOException("Incomplete download: received $length of $totalSize bytes")
                }
                verifyDownloadLength(length, totalSize)
                return length
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                attempts++
                if (attempts >= MAX_DOWNLOAD_ATTEMPTS) throw e
                Log.w(
                    "YTExtDownloads",
                    "Download $id interrupted at ${destination.length()}B; retry $attempts/${MAX_DOWNLOAD_ATTEMPTS - 1}",
                    e,
                )
                delay(RETRY_BASE_DELAY_MS * attempts)
            }
        }
    }

    private fun downloadAttempt(
        urlString: String,
        destination: File,
        initialOffset: Long,
        knownTotal: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Long {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", request.mediaUserAgent)
        connection.setRequestProperty("Accept", "*/*")
        connection.setRequestProperty("Accept-Encoding", "identity")

        var fileOffset = initialOffset
        var append = fileOffset > 0L
        var total = knownTotal

        try {
            if (fileOffset > 0L) {
                connection.setRequestProperty("Range", "bytes=$fileOffset-")
            }

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_OK && fileOffset > 0L) {
                // The CDN ignored the Range header. Start the file over instead of
                // appending the whole stream to the bytes we already have.
                fileOffset = 0L
                append = false
                total = 0L
            } else if (status == HttpURLConnection.HTTP_PARTIAL) {
                total = parseTotalSize(connection.getHeaderField("Content-Range"))
                    .takeIf { it > 0L }
                    ?: (fileOffset + connection.contentLengthLong).takeIf { it > 0L }
                    ?: knownTotal
            } else if (status == 416) {
                if (total > 0L && fileOffset >= total) return total
                throw IOException("Media server rejected resume at byte $fileOffset")
            } else if (status !in 200..299) {
                throw IOException("Media server returned HTTP $status")
            }

            verifyMediaContentType(connection)

            if (status == HttpURLConnection.HTTP_OK) {
                val connectionTotal = connection.contentLengthLong
                if (connectionTotal > 0L) total = connectionTotal
            }

            val input = BufferedInputStream(connection.getInputStream(), 64 * 1024)
            val output = FileOutputStream(destination, append)
            val buffer = ByteArray(64 * 1024)
            var readFromThisAttempt = 0L

            try {
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    readFromThisAttempt += read
                    onProgress(fileOffset + readFromThisAttempt, total)
                }
            } finally {
                output.close()
                input.close()
            }

            return total
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTotalSize(contentRange: String?): Long {
        val total = contentRange?.substringAfterLast('/')?.trim() ?: return -1L
        return total.toLongOrNull() ?: -1L
    }

    private companion object {
        const val MAX_DOWNLOAD_ATTEMPTS = 6
        const val RETRY_BASE_DELAY_MS = 700L
    }
}

/**
 * Direct stream download — single URL written straight to MediaStore.
 */
class DirectDownloadTask(
    id: Long,
    request: DownloadRequest,
    val directUrl: String,
    manager: VideoDownloadManager,
) : DownloadTask(id, request, manager) {

    override suspend fun doRun(): Uri = withContext(Dispatchers.IO) {
        val tempFile = File(manager.context.cacheDir, "direct_$id.${request.format.extension}")
        var pending: MediaStoreHelper.PendingVideo? = null

        try {
            downloadToFile(directUrl, tempFile) { downloaded, total ->
                // 100% is reserved for the validated, published file.
                val percent = if (total > 0) {
                    ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                } else {
                    null
                }
                updateProgress(
                    downloaded = downloaded,
                    total = if (total > 0) total else 0L,
                    percent = percent,
                )
            }

            validateMediaFile(
                file = tempFile,
                requireVideo = request.format != ContainerFormat.M4A,
                requireAudio = request.format == ContainerFormat.M4A,
            )

            updateProgress(
                downloaded = tempFile.length(),
                total = tempFile.length().coerceAtLeast(1L),
                percent = 99,
            )

            // Only create the MediaStore row after the file has passed validation.
            pending = MediaStoreHelper.create(manager.context, request)
            pending.openOutputStream().use { outputStream ->
                tempFile.inputStream().use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
            }
            pending.complete()
            pending.uri
        } catch (e: Exception) {
            pending?.cancel()
            throw e
        } finally {
            tempFile.delete()
        }
    }
}

/**
 * Separate streams download — video + audio downloaded to temp files, then muxed.
 */
class SeparateStreamsDownloadTask(
    id: Long,
    request: DownloadRequest,
    val videoUrl: String,
    val audioUrl: String,
    private val expectedVideoBytes: Long,
    private val expectedAudioBytes: Long,
    manager: VideoDownloadManager,
) : DownloadTask(id, request, manager) {

    override suspend fun doRun(): Uri = withContext(Dispatchers.IO) {
        val tempDir = manager.context.cacheDir
        val videoFile = File(tempDir, "video_$id.tmp")
        val audioFile = File(tempDir, "audio_$id.tmp")
        val muxedFile = File(tempDir, "muxed_$id.${request.format.extension}")
        val expectedTotal = when {
            expectedVideoBytes > 0 && expectedAudioBytes > 0 -> expectedVideoBytes + expectedAudioBytes
            else -> 0L
        }
        val videoWeight = when {
            expectedVideoBytes > 0 && expectedTotal > 0 ->
                expectedVideoBytes.toDouble() / expectedTotal.toDouble()
            else -> 0.5
        }
        // Streams take 90% of the bar; the first stream gets a share that matches its
        // byte size and the second one fills the remainder.
        val videoEndPercent = (videoWeight * 90.0).roundToInt().coerceIn(5, 85)

        try {
            downloadToFile(videoUrl, videoFile) { downloaded, connectionTotal ->
                val total = when {
                    expectedVideoBytes > 0 -> expectedVideoBytes
                    connectionTotal > 0 -> connectionTotal
                    else -> 0L
                }
                val percent = if (total > 0) {
                    ((downloaded * videoEndPercent) / total).toInt().coerceIn(0, videoEndPercent)
                } else {
                    -1
                }
                updateProgress(
                    downloaded = downloaded,
                    total = if (expectedTotal > 0) expectedTotal else total,
                    percent = percent,
                )
            }

            checkCancelled()
            validateMediaFile(videoFile, requireVideo = true, requireAudio = false)

            val videoBytes = videoFile.length()
            downloadToFile(audioUrl, audioFile) { downloaded, connectionTotal ->
                val total = when {
                    expectedAudioBytes > 0 -> expectedAudioBytes
                    connectionTotal > 0 -> connectionTotal
                    else -> 0L
                }
                val percent = if (total > 0) {
                    videoEndPercent +
                        ((downloaded * (90 - videoEndPercent)) / total).toInt()
                            .coerceIn(0, 90 - videoEndPercent)
                } else {
                    -1
                }
                updateProgress(
                    downloaded = videoBytes + downloaded,
                    total = if (expectedTotal > 0) expectedTotal else videoBytes + total,
                    speedBytes = videoBytes + downloaded,
                    percent = percent,
                )
            }

            checkCancelled()
            validateMediaFile(audioFile, requireVideo = false, requireAudio = true)

            muxStreams(videoFile, audioFile, muxedFile) { phasePercent ->
                updateMuxingProgress(phasePercent)
            }

            checkCancelled()
            validateMediaFile(muxedFile, requireVideo = true, requireAudio = true)

            val pending = MediaStoreHelper.create(manager.context, request)
            try {
                updateMuxingProgress(99)
                pending.openOutputStream().use { output ->
                    muxedFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                    output.flush()
                }
                pending.complete()
                pending.uri
            } catch (e: Exception) {
                pending.cancel()
                throw e
            }
        } finally {
            videoFile.delete()
            audioFile.delete()
            muxedFile.delete()
        }
    }

    private fun muxStreams(
        videoFile: File,
        audioFile: File,
        tempOutput: File,
        onProgress: (Int) -> Unit,
    ) {
        val outputPfd = ParcelFileDescriptor.open(
            tempOutput,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        )

        val muxer = MediaMuxer(
            outputPfd.fileDescriptor,
            if (request.format == ContainerFormat.MP4)
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            else
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
        )

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxerStarted = false

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrackIndex = selectTrack(videoExtractor, true)
            if (videoTrackIndex < 0) throw IOException("No video track found")
            videoExtractor.selectTrack(videoTrackIndex)
            val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val videoDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION)

            audioExtractor.setDataSource(audioFile.absolutePath)
            val audioTrackIndex = selectTrack(audioExtractor, false)
            if (audioTrackIndex < 0) throw IOException("No audio track found")
            audioExtractor.selectTrack(audioTrackIndex)
            val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
            val audioDurationUs = audioFormat.getLong(MediaFormat.KEY_DURATION)

            val muxedVideoTrack = muxer.addTrack(videoFormat)
            val muxedAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()
            muxerStarted = true

            val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var lastReportedPercent = -1
            fun reportMuxProgress(percent: Int) {
                if (percent == lastReportedPercent) return
                lastReportedPercent = percent
                onProgress(percent)
            }

            while (true) {
                checkCancelled()
                bufferInfo.offset = 0
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(muxedVideoTrack, buffer, bufferInfo)
                if (videoDurationUs > 0) {
                    reportMuxProgress(
                        (videoExtractor.sampleTime * 80L / videoDurationUs)
                            .toInt()
                            .coerceIn(0, 80),
                    )
                }
                videoExtractor.advance()
            }

            while (true) {
                checkCancelled()
                bufferInfo.offset = 0
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(muxedAudioTrack, buffer, bufferInfo)
                if (audioDurationUs > 0) {
                    reportMuxProgress(
                        80 + (audioExtractor.sampleTime * 19L / audioDurationUs)
                            .toInt()
                            .coerceIn(0, 19),
                    )
                }
                audioExtractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            reportMuxProgress(99)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { outputPfd.close() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, wantVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (wantVideo && mime.startsWith("video/")) return i
            if (!wantVideo && mime.startsWith("audio/")) return i
        }
        return -1
    }
}
