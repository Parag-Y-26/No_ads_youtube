package com.ytextapp.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Creates videos in the shared `Movies/YTExt` folder and hands the caller a stream to
 * write them.
 *
 * Android 10 moved shared storage behind MediaStore, so there are two paths:
 *
 * - **API 29+** — insert a row with `IS_PENDING = 1`, write to the returned content URI,
 *   then clear the flag. Other apps cannot see the half-written file in the meantime,
 *   and no storage permission is involved at all.
 * - **API 26–28** — write a real file under `Environment.DIRECTORY_MOVIES/YTExt` and ask
 *   the media scanner to index it once it is complete. This path needs
 *   `WRITE_EXTERNAL_STORAGE` granted at runtime.
 *
 * All methods touch disk or the ContentResolver, so call them off the main thread.
 * A download must always end in either [PendingVideo.complete] or
 * [PendingVideo.cancel]; otherwise the entry stays pending (API 29+) or the partial file
 * stays on the volume (API 26–28).
 */
object MediaStoreHelper {

    /** Folder inside Movies that holds every download. */
    const val ALBUM_NAME = "YTExt"

    /** Keeps the base name far below the limit of any filesystem the app may write to. */
    private const val MAX_BASE_NAME_LENGTH = 100

    /** Used when the title sanitises down to nothing. */
    private const val FALLBACK_FILE_NAME = "video"

    /** Characters that are illegal in a file name on Android's FAT/exFAT volumes. */
    private val INVALID_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val WHITESPACE = Regex("""\s+""")

    private val RELATIVE_PATH = "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"

    /**
     * A destination that is still being written.
     *
     * [uri] points at the MediaStore row on API 29+ and at the real file on API 26–28,
     * so it is only safe to share after [complete].
     */
    class PendingVideo internal constructor(
        val uri: Uri,
        val displayName: String,
        private val context: Context,
        private val resolver: ContentResolver,
        /** Non-null only on API 26–28, where no MediaStore row exists yet. */
        private val legacyFile: File?,
    ) {

        /**
         * Opens the destination for writing. The caller owns the stream and must close
         * it before calling [complete]; until then the file stays invisible to galleries
         * on API 29+.
         */
        fun openOutputStream(): OutputStream {
            legacyFile?.let { return FileOutputStream(it) }

            return resolver.openOutputStream(uri)
                ?: throw IOException("Cannot open $uri for writing")
        }

        /** Publishes the finished file so galleries and other apps can see it. */
        fun complete() {
            val file = legacyFile
            if (file != null) {
                // Pre-Q files only appear in galleries once the scanner has indexed them.
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                clearPendingFlag()
            }
        }

        /** Deletes the partial file, for example after a failed or cancelled transfer. */
        fun cancel() {
            runCatching {
                val file = legacyFile
                if (file != null) file.delete() else resolver.delete(uri, null, null)
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun clearPendingFlag() {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            resolver.update(uri, values, null, null)
        }
    }

    /**
     * Creates the destination for [request] and sanitises the title into a file name.
     *
     * @throws IOException when the album directory cannot be created or MediaStore
     *   refuses the insert.
     */
    fun create(context: Context, request: DownloadRequest): PendingVideo {
        val displayName = "${sanitizeFileName(request.title)}.${request.format.extension}"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createWithMediaStore(context, request, displayName)
        } else {
            createLegacyFile(context, displayName)
        }
    }

    /**
     * Turns a video title into a file name that every supported volume accepts.
     *
     * Path separators are replaced rather than stripped so `A/B` does not silently
     * become `AB`; repeated whitespace is collapsed because YouTube titles are full of
     * it; leading/trailing dots go because a leading dot hides the file and a trailing
     * dot is invalid on FAT. The result is never empty.
     */
    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .replace(INVALID_FILE_NAME_CHARS, "_")
            .replace(WHITESPACE, " ")
            .trim()
            .trim('.')
            .take(MAX_BASE_NAME_LENGTH)
            .trim()
            .trim('.')

        return cleaned.ifEmpty { FALLBACK_FILE_NAME }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createWithMediaStore(
        context: Context,
        request: DownloadRequest,
        displayName: String,
    ): PendingVideo {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, request.format.mimeType)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            // Hides the row from other apps until the last byte has been written.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore refused to create $displayName")

        return PendingVideo(
            uri = uri,
            displayName = displayName,
            context = context.applicationContext,
            resolver = resolver,
            legacyFile = null,
        )
    }

    /**
     * API 26–28 fallback. MediaStore cannot own a file the app merely creates, so this
     * writes straight into the public Movies directory and relies on the media scanner
     * at [PendingVideo.complete] time.
     */
    @Suppress("DEPRECATION")
    private fun createLegacyFile(context: Context, displayName: String): PendingVideo {
        val movies = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        )
        val album = File(movies, ALBUM_NAME)
        if (!album.isDirectory && !album.mkdirs()) {
            throw IOException("Cannot create ${album.absolutePath}")
        }

        val file = uniqueFile(album, displayName)

        return PendingVideo(
            uri = Uri.fromFile(file),
            displayName = file.name,
            context = context.applicationContext,
            resolver = context.contentResolver,
            legacyFile = file,
        )
    }

    /**
     * MediaStore de-duplicates display names itself on API 29+, but a raw [File] write
     * would silently overwrite an earlier download, so the legacy path picks the next
     * free `Title (1).mp4` style name.
     */
    private fun uniqueFile(directory: File, displayName: String): File {
        var candidate = File(directory, displayName)
        if (!candidate.exists()) return candidate

        val extension = displayName.substringAfterLast('.', "")
        val base = displayName.substringBeforeLast('.', displayName)

        var index = 1
        while (candidate.exists()) {
            val suffix = " ($index)"
            val name = if (extension.isEmpty()) "$base$suffix" else "$base$suffix.$extension"
            candidate = File(directory, name)
            index++
        }
        return candidate
    }
}
