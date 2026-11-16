package com.ytextapp.download

/**
 * Container a downloaded video is saved in.
 *
 * The extension and MIME type are kept together so the file name, the MediaStore row
 * and any consumer of the format cannot drift apart.
 */
enum class ContainerFormat(val extension: String, val mimeType: String) {

    MP4("mp4", "video/mp4"),
    MKV("mkv", "video/x-matroska"),
    M4A("m4a", "audio/mp4");

    companion object {
        /**
         * Parses a stored or user-supplied extension, with or without the leading dot,
         * case-insensitively. Returns null when the value is absent or unrecognised, so
         * callers can tell "not set" apart from a real choice.
         */
        fun fromExtension(extension: String?): ContainerFormat? {
            if (extension.isNullOrBlank()) return null

            val normalized = extension.trim().removePrefix(".").lowercase()
            return entries.firstOrNull { it.extension == normalized }
        }
    }
}

/**
 * Everything the download pipeline needs to fetch one video.
 *
 * [url] is the already-resolved media URL (the quality decision happened when it was
 * obtained); [quality] is kept for the history row and for display; [format] decides the
 * container, file extension and MIME type.
 */
data class DownloadRequest(
    val videoId: String,
    val url: String,
    val title: String,
    val format: ContainerFormat,
    val quality: String,
    /**
     * User agent of the InnerTube client that produced the media URL. googlevideo
     * URLs can be bound to the resolving client, so the download must not switch to
     * a different agent halfway through.
     */
    val mediaUserAgent: String = YouTubeStreamResolver.MEDIA_USER_AGENT,
)
