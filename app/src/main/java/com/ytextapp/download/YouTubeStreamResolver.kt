package com.ytextapp.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Resolves a YouTube video id into real, downloadable media URLs.
 *
 * Why this exists: the watch page plays through MediaSource. The `<video>` element only
 * ever has a `blob:` URL, and the player response embedded in the page contains
 * `signatureCipher` entries instead of playable URLs. Saving the page URL therefore
 * produced an HTML file, not a video.
 *
 * The InnerTube player endpoint accepts a handful of official clients. The Android VR
 * client still returns plain `url` fields (no cipher, no PoToken) for the formats it
 * exposes, so it is used here. Requests are made directly with OkHttp; no extra extractor
 * library is required.
 */
object YouTubeStreamResolver {

    private const val TAG = "YTExtResolver"

    private const val PLAYER_URL =
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

    /**
     * The VR client is identified partly by its user agent. The same value is used for
     * the media requests in [VideoDownloadManager]; googlevideo rejects a mismatched
     * agent on some URLs.
     */
    const val MEDIA_USER_AGENT =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip"

    /**
     * One InnerTube client identity. YouTube has been rolling out bot checks to
     * individual clients; and [resolve] walks this list until one returns playable,
     * unciphered URLs.
     */
    private data class PlayerClient(
        val name: String,
        val version: String,
        val userAgent: String,
        val extra: Map<String, Any> = emptyMap(),
    )

    private val PLAYER_CLIENTS = listOf(
        PlayerClient(
            name = "ANDROID_VR",
            version = "1.60.19",
            userAgent = MEDIA_USER_AGENT,
            extra = mapOf(
                "deviceMake" to "Oculus",
                "deviceModel" to "Quest 3",
                "androidSdkVersion" to 32,
                "osName" to "Android",
                "osVersion" to "12",
            ),
        ),
        // Fallback used when the VR client is challenged ("Sign in to confirm you're
        // not a bot"). Android and iOS still return plain `url` fields for the formats
        // they expose, so no signature deciphering is needed.
        PlayerClient(
            name = "ANDROID",
            version = "20.10.38",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US; Pixel 8 Pro Build/AP1A.240305.019.A1) gzip",
            extra = mapOf(
                "deviceMake" to "Google",
                "deviceModel" to "Pixel 8 Pro",
                "androidSdkVersion" to 34,
                "osName" to "Android",
                "osVersion" to "14",
            ),
        ),
        PlayerClient(
            name = "IOS",
            version = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)",
            extra = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.1.22D72",
            ),
        ),
    )

    /** Stream URLs carry an expiry; a short cache avoids duplicate player calls when
     *  the user closes and reopens the sheet, without risking an expired URL. */
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cacheLock = Any()
    private var cachedVideoId: String? = null
    private var cachedAt: Long = 0L
    private var cachedResult: ResolvedVideo? = null

    /**
     * One video, already turned into user-facing download choices.
     */
    data class ResolvedVideo(
        val videoId: String,
        val title: String,
        val author: String,
        val durationSeconds: Long,
        val thumbnailUrl: String?,
        val options: List<DownloadOption>,
    )

    enum class DownloadKind { VIDEO, AUDIO }

    /**
     * A single row in the download sheet.
     *
     * [audioUrl] is set only for a video-only stream; the download manager then fetches
     * both streams and muxes them. [sizeBytes] is the video size, or the whole file for
     * audio. [audioSizeBytes] is added when showing the total for a muxed option.
     */
    data class DownloadOption(
        val id: String,
        val title: String,
        val subtitle: String,
        val kind: DownloadKind,
        val format: ContainerFormat,
        val quality: String,
        val url: String,
        val audioUrl: String?,
        val sizeBytes: Long,
        val audioSizeBytes: Long,
        val featured: Boolean,
        val sizeIsApproximate: Boolean,
        /** Must be sent with both media requests; see [DownloadRequest.mediaUserAgent]. */
        val mediaUserAgent: String,
    ) {
        val needsMux: Boolean get() = audioUrl != null
        val totalSizeBytes: Long get() = sizeBytes + audioSizeBytes
    }

    private data class Stream(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val codecs: String,
        val qualityLabel: String?,
        val audioQuality: String?,
        val bitrate: Long,
        val contentLength: Long,
        val isVideo: Boolean,
    ) {
        val isAvc: Boolean get() = codecs.startsWith("avc1")
        val isAac: Boolean get() = codecs.startsWith("mp4a")
        val hasAudio: Boolean get() {
            if (!isVideo) return false
            return !audioQuality.isNullOrBlank() || codecs.contains("mp4a") || codecs.contains("opus")
        }
        val height: Int get() = qualityLabel?.removeSuffix("p")?.toIntOrNull() ?: 0
    }

    /**
     * Fetches formats for [videoId]. Safe to call from the main thread; the network
     * request and JSON parsing run on [Dispatchers.IO].
     */
    suspend fun resolve(
        videoId: String,
        fallbackTitle: String,
    ): ResolvedVideo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cached = cachedResult
            if (cached != null &&
                cachedVideoId == videoId &&
                now - cachedAt < CACHE_TTL_MS
            ) {
                return@withContext cached
            }
        }

        var lastError: IOException? = null
        for (playerClient in PLAYER_CLIENTS) {
            try {
                val response = fetchPlayerResponse(videoId, playerClient)
                val result = parseResolvedVideo(response, videoId, fallbackTitle, playerClient.userAgent)

                synchronized(cacheLock) {
                    cachedVideoId = videoId
                    cachedAt = System.currentTimeMillis()
                    cachedResult = result
                }

                Log.d(
                    TAG,
                    "Resolved $videoId via ${playerClient.name}: ${result.options.size} options",
                )
                return@withContext result
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                lastError = e as? IOException ?: IOException(reason, e)
                Log.w(TAG, "${playerClient.name} could not resolve $videoId: $reason")
            }
        }

        throw lastError ?: IOException("Could not resolve this video")
    }

    private fun parseResolvedVideo(
        response: JSONObject,
        videoId: String,
        fallbackTitle: String,
        mediaUserAgent: String,
    ): ResolvedVideo {
        val playability = response.optJSONObject("playabilityStatus")
        val status = playability?.optString("status").orEmpty()
        if (status != "OK") {
            val reason = playability?.optString("reason")
                ?.takeIf { it.isNotBlank() }
                ?: "This video cannot be played"
            throw IOException(reason)
        }

        val details = response.optJSONObject("videoDetails")
        val title = details?.optString("title")
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        val author = details?.optString("author").orEmpty()
        val durationSeconds = details?.optString("lengthSeconds")?.toLongOrNull() ?: 0L

        val thumbnailUrl = details
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.bestThumbnailUrl()

        val streamingData = response.optJSONObject("streamingData")
            ?: throw IOException("YouTube returned no stream information")

        val formats = streamingData.optJSONArray("formats").toStreams()
        val adaptive = streamingData.optJSONArray("adaptiveFormats").toStreams()

        val progressive = formats.filter { it.isVideo && it.hasAudio }
        val videoOnly = adaptive.filter { it.isVideo && !it.hasAudio }
        val audio = adaptive.filter { !it.isVideo && it.mimeType.startsWith("audio/") }

        val options = buildOptions(progressive, videoOnly, audio, mediaUserAgent)
        if (options.isEmpty()) {
            throw IOException("No downloadable formats were returned for this video")
        }

        return ResolvedVideo(
            videoId = videoId,
            title = title,
            author = author,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            options = options,
        )
    }

    private fun fetchPlayerResponse(videoId: String, playerClient: PlayerClient): JSONObject {
        val clientContext = JSONObject().apply {
            put("clientName", playerClient.name)
            put("clientVersion", playerClient.version)
            put("hl", "en")
            put("gl", "US")
            put("userAgent", playerClient.userAgent)
            for ((key, value) in playerClient.extra) put(key, value)
        }

        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", clientContext)
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val request = Request.Builder()
            .url(PLAYER_URL)
            .header("User-Agent", playerClient.userAgent)
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Player request failed (HTTP ${response.code})")
            }
            if (text.isBlank()) throw IOException("YouTube returned an empty response")
            return JSONObject(text)
        }
    }

    private fun JSONArray?.toStreams(): List<Stream> {
        if (this == null) return emptyList()
        val result = ArrayList<Stream>(length())
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            // Ciphered URLs are not usable without running YouTube's player
            // JavaScript. The VR client normally returns plain URLs; if a format is
            // ciphered for some video, skip that format instead of failing everything.
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: continue
            val mime = item.optString("mimeType")
            val codecs = CODECS_REGEX.find(mime)?.groupValues?.getOrNull(1).orEmpty()
            val contentLength = item.optLong("contentLength", 0L)
            val bitrate = item.optLong("bitrate", 0L)
            val approxDurationMs = item.optLong("approxDurationMs", 0L)
            val estimatedLength = if (contentLength > 0) {
                contentLength
            } else if (bitrate > 0 && approxDurationMs > 0) {
                // bits per second -> bytes for the duration
                bitrate * approxDurationMs / 8000L
            } else {
                0L
            }

            result += Stream(
                itag = item.optInt("itag", -1),
                url = url,
                mimeType = mime,
                codecs = codecs,
                qualityLabel = item.optString("qualityLabel").takeIf { it.isNotBlank() },
                audioQuality = item.optString("audioQuality").takeIf { it.isNotBlank() },
                bitrate = bitrate,
                contentLength = estimatedLength,
                isVideo = mime.startsWith("video/"),
            )
        }
        return result
    }

    private fun buildOptions(
        progressive: List<Stream>,
        videoOnly: List<Stream>,
        audio: List<Stream>,
        mediaUserAgent: String,
    ): List<DownloadOption> {
        val options = mutableListOf<DownloadOption>()

        // Prefer AAC for MP4 muxing; it is playable everywhere on Android.
        val audioStreams = audio.sortedWith(
            compareByDescending<Stream> { it.isAac }.thenByDescending { it.bitrate },
        )
        val bestAudio = audioStreams.firstOrNull { it.isAac }
            ?: audioStreams.firstOrNull()

        // Audio-only: the best AAC stream and a low-bitrate alternative.
        audioStreams.filter { it.isAac }.distinctBy { it.itag }.forEach { stream ->
            val label = when {
                stream.bitrate >= 100_000 -> "High quality"
                stream.bitrate in 60_000..99_999 -> "Standard"
                else -> "Data saver"
            }
            val kbps = if (stream.bitrate > 0) " · ${stream.bitrate / 1000} kbps" else ""
            options += DownloadOption(
                id = "audio-${stream.itag}",
                title = label,
                subtitle = "M4A$kbps · ${formatBytes(stream.contentLength, stream.contentLength <= 0)}",
                kind = DownloadKind.AUDIO,
                format = ContainerFormat.M4A,
                quality = "audio",
                url = stream.url,
                audioUrl = null,
                sizeBytes = stream.contentLength,
                audioSizeBytes = 0L,
                featured = true,
                sizeIsApproximate = stream.contentLength <= 0,
                mediaUserAgent = mediaUserAgent,
            )
        }

        val videoOptions = mutableListOf<DownloadOption>()

        // Progressive streams need no muxing and are the most reliable choice.
        progressive
            .filter { it.mimeType.startsWith("video/mp4") && it.height > 0 }
            .sortedByDescending { it.height }
            .forEach { stream ->
                val quality = stream.qualityLabel ?: "${stream.height}p"
                videoOptions += DownloadOption(
                    id = "progressive-${stream.itag}",
                    title = "Fast",
                    subtitle = "$quality · MP4 · ${formatBytes(stream.contentLength, stream.contentLength <= 0)}",
                    kind = DownloadKind.VIDEO,
                    format = ContainerFormat.MP4,
                    quality = quality,
                    url = stream.url,
                    audioUrl = null,
                    sizeBytes = stream.contentLength,
                    audioSizeBytes = 0L,
                    featured = true,
                    sizeIsApproximate = stream.contentLength <= 0,
                    mediaUserAgent = mediaUserAgent,
                )
            }

        if (bestAudio != null) {
            // AVC video + AAC audio can always be muxed into an MP4 by MediaMuxer.
            videoOnly
                .filter { it.isAvc && it.height > 0 }
                .sortedByDescending { it.height }
                .distinctBy { it.height }
                .forEach { stream ->
                    val quality = stream.qualityLabel ?: "${stream.height}p"
                    val title = when {
                        stream.height >= 1080 -> "Full HD"
                        stream.height >= 720 -> "HD"
                        else -> "Standard"
                    }
                    val featured = stream.height >= 720
                    videoOptions += DownloadOption(
                        id = "avc-${stream.itag}",
                        title = title,
                        subtitle = "$quality · MP4 · ${formatBytes(stream.contentLength + bestAudio.contentLength, false)}",
                        kind = DownloadKind.VIDEO,
                        format = ContainerFormat.MP4,
                        quality = quality,
                        url = stream.url,
                        audioUrl = bestAudio.url,
                        sizeBytes = stream.contentLength,
                        audioSizeBytes = bestAudio.contentLength,
                        featured = featured,
                        sizeIsApproximate = stream.contentLength <= 0,
                        mediaUserAgent = mediaUserAgent,
                    )
                }
        }

        // De-duplicate by quality; a progressive 360p should win over a muxed 360p
        // because it is a single request and needs no processing.
        val seenVideoQualities = mutableSetOf<String>()
        val orderedVideo = videoOptions.sortedWith(
            compareByDescending<DownloadOption> { it.featured }
                .thenByDescending { qualityHeight(it.quality) },
        )
        orderedVideo.forEach { option ->
            if (seenVideoQualities.add(option.quality)) options += option
        }

        return options
    }

    private fun qualityHeight(quality: String): Int =
        quality.removeSuffix("p").toIntOrNull() ?: 0

    private fun JSONArray.bestThumbnailUrl(): String? {
        var bestUrl: String? = null
        var bestArea = 0
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val url = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val area = item.optInt("width") * item.optInt("height")
            if (url.isNotEmpty() && (bestUrl == null || area >= bestArea)) {
                bestUrl = url
                bestArea = area
            }
        }
        return bestUrl
    }

    fun formatBytes(bytes: Long, approximate: Boolean = false): String {
        if (bytes <= 0) return "size unknown"
        val prefix = if (approximate) "~" else ""
        return when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%s%.1f GB", prefix, bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format(Locale.US, "%s%.1f MB", prefix, bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%s%.0f KB", prefix, bytes / 1_000.0)
            else -> "$bytes B"
        }
    }

    private val CODECS_REGEX = Regex("""codecs="([^"]+)"""")
}

/**
 * UI state while the sheet waits for [YouTubeStreamResolver].
 */
sealed interface StreamResolution {
    data object Loading : StreamResolution
    data class Ready(val video: YouTubeStreamResolver.ResolvedVideo) : StreamResolution
    data class Error(val message: String) : StreamResolution
}
