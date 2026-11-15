package com.ytextapp.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    fun startDirectDownload(request: DownloadRequest, directUrl: String): Long {
        val downloadId = System.currentTimeMillis()
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
    ): Long {
        val downloadId = System.currentTimeMillis()
        val task = SeparateStreamsDownloadTask(
            id = downloadId,
            request = request,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
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
        activeDownloads.remove(id)
        if (activeDownloads.isEmpty()) {
            _progress.tryEmit(null)
        }
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
    ) : DownloadProgress(id, title)

    data class Muxing(
        override val id: Long,
        override val title: String,
        val progressPercent: Int,
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
    ) {
        bytesDownloaded.set(downloaded)
        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1) / 1000f
        val speed = if (elapsed > 0) (downloaded / elapsed).toLong() else 0L

        manager.updateProgress(DownloadProgress.Downloading(
            id = id,
            title = request.title,
            bytesDownloaded = downloaded,
            totalBytes = total,
            speedBps = speed,
        ))

        val notification = buildNotification(downloaded, total, speed)
        manager.notificationManager.notify(manager.getNotificationId(id), notification)
    }

    protected fun buildNotification(
        downloaded: Long,
        total: Long,
        speedBps: Long,
    ): Notification {
        val percent: Int = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val speedStr = formatSpeed(speedBps)
        val downloadedStr = formatBytes(downloaded)
        val totalStr = if (total > 0) formatBytes(total) else "?"

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
            .setContentText("$percent% · $downloadedStr / $totalStr · $speedStr")
            .setProgress(100, percent, false)
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
            .setContentText("Muxing streams… $percent%")
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
            doRun()
            manager.updateProgress(DownloadProgress.Completed(id, request.title, Uri.EMPTY))
        } catch (e: CancellationException) {
            manager.updateProgress(DownloadProgress.Cancelled(id, request.title))
        } catch (e: Exception) {
            manager.updateProgress(DownloadProgress.Failed(id, request.title, e.message ?: e.javaClass.simpleName))
        } finally {
            manager.notificationManager.cancel(manager.getNotificationId(id))
            manager.removeTask(id)
        }
    }

    protected abstract suspend fun doRun()
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

    override suspend fun doRun() = withContext(Dispatchers.IO) {
        val pending = MediaStoreHelper.create(manager.context, request)
        val outputStream = pending.openOutputStream()
        try {
            downloadStream(directUrl, outputStream)
            pending.complete()
        } catch (e: Exception) {
            pending.cancel()
            throw e
        } finally {
            outputStream.close()
        }
    }

    private suspend fun downloadStream(urlString: String, output: OutputStream) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true

        try {
            val totalSize = connection.contentLengthLong
            val input = BufferedInputStream(connection.getInputStream(), 64 * 1024)
            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L

            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                totalRead += read
                updateProgress(totalRead, if (totalSize > 0) totalSize else 0)
            }
        } finally {
            connection.disconnect()
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
    manager: VideoDownloadManager,
) : DownloadTask(id, request, manager) {

    override suspend fun doRun() = withContext(Dispatchers.IO) {
        val tempDir = manager.context.cacheDir
        val videoFile = File(tempDir, "video_$id.tmp")
        val audioFile = File(tempDir, "audio_$id.tmp")

        try {
            downloadStream(videoUrl, videoFile) { progress ->
                updateProgress(progress, videoFile.length())
            }

            checkCancelled()

            downloadStream(audioUrl, audioFile) { progress ->
                updateProgress(progress, audioFile.length())
            }

            checkCancelled()

            val pending = MediaStoreHelper.create(manager.context, request)
            muxStreams(videoFile, audioFile, pending.openOutputStream()) { percent ->
                manager.updateProgress(DownloadProgress.Muxing(id, request.title, percent))
                val notification = buildMuxingNotification(percent)
                manager.notificationManager.notify(manager.getNotificationId(id), notification)
            }
            pending.complete()

        } catch (e: Exception) {
            videoFile.delete()
            audioFile.delete()
            throw e
        } finally {
            videoFile.delete()
            audioFile.delete()
        }
    }

    private suspend fun downloadStream(
        urlString: String,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true

        try {
            val input = BufferedInputStream(connection.getInputStream(), 64 * 1024)
            val output = FileOutputStream(destination)
            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L

            try {
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalRead += read
                    onProgress(totalRead)
                }
            } finally {
                output.close()
                input.close()
            }
        } finally {
            connection.disconnect()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun muxStreams(
        videoFile: File,
        audioFile: File,
        outputStream: OutputStream,
        onProgress: (Int) -> Unit,
    ) {
        val tempOutput = File(manager.context.cacheDir, "muxed_$id.${request.format.extension}")
        val outputFd = ParcelFileDescriptor.open(
            tempOutput,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
        ).fileDescriptor

        val muxer = MediaMuxer(
            outputFd,
            if (request.format == ContainerFormat.MP4)
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            else
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM,
        )

        val videoExtractor = android.media.MediaExtractor()
        videoExtractor.setDataSource(videoFile.absolutePath)
        val videoTrackIndex = selectTrack(videoExtractor, true)
        if (videoTrackIndex < 0) throw IOException("No video track found")
        videoExtractor.selectTrack(videoTrackIndex)

        val audioExtractor = android.media.MediaExtractor()
        audioExtractor.setDataSource(audioFile.absolutePath)
        val audioTrackIndex = selectTrack(audioExtractor, false)
        if (audioTrackIndex < 0) throw IOException("No audio track found")
        audioExtractor.selectTrack(audioTrackIndex)

        val muxedVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
        val muxedAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))

        muxer.start()

        val buffer = java.nio.ByteBuffer.allocate(128 * 1024)
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        var sampleCount = 0
        while (true) {
            checkCancelled()
            bufferInfo.offset = 0
            bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) break
            bufferInfo.presentationTimeUs = videoExtractor.sampleTime
            bufferInfo.flags = videoExtractor.sampleFlags
            muxer.writeSampleData(muxedVideoTrack, buffer, bufferInfo)
            videoExtractor.advance()
            sampleCount++
            if (sampleCount % 30 == 0) {
                onProgress(30 + (sampleCount * 30 / 100).coerceAtMost(50))
            }
        }

        while (true) {
            checkCancelled()
            bufferInfo.offset = 0
            bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
            if (bufferInfo.size < 0) break
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = audioExtractor.sampleFlags
            muxer.writeSampleData(muxedAudioTrack, buffer, bufferInfo)
            audioExtractor.advance()
        }

        muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()

        onProgress(90)

        val input = tempOutput.inputStream()
        input.copyTo(outputStream)
        outputStream.flush()
        input.close()

        onProgress(100)
    }

    private fun selectTrack(extractor: android.media.MediaExtractor, wantVideo: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
            if (wantVideo && mime.startsWith("video/")) return i
            if (!wantVideo && mime.startsWith("audio/")) return i
        }
        return -1
    }
}