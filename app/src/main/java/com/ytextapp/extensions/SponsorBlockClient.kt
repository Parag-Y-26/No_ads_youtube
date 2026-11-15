package com.ytextapp.extensions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class SponsorBlockClient {

    data class Segment(
        val start: Double,
        val end: Double,
        val category: String,
        val uuid: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Looks the video up by the SHA-256 hash prefix of its id.
     *
     * The plain `?videoID=` query told the SponsorBlock server exactly what was being
     * watched. A prefix (the API minimum is four hex characters) only narrows it to the
     * handful of videos that share those 16 bits, and the response carries a `videoID`
     * per entry, so the filtering happens locally.
     */
    suspend fun getSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        try {
            val url = "$API_BASE/api/skipSegments/${sha256Prefix(videoId)}"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            // `use` closes the body on every path, including the non-2xx ones.
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()

                val body = response.body?.string() ?: return@withContext emptyList()
                parseSegments(body, videoId)
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: the caller cancels this request when the
            // video changes, and reporting "no segments" would look like a success.
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * A hash prefix returns several videos, each with a nested `segments` array:
     *
     * ```json
     * [{"videoID": "abc", "segments": [{"segment": [1.2, 3.4], "category": "sponsor"}]}]
     * ```
     *
     * Only the entries belonging to [videoId] are kept. Each segment is parsed
     * independently — a single malformed record (a POI has a one-element `segment`
     * array, for instance) must not discard the rest of the response.
     */
    private fun parseSegments(json: String, videoId: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                try {
                    val entry = jsonArray.getJSONObject(i)
                    if (entry.optString("videoID") != videoId) continue

                    val nested = entry.optJSONArray("segments")
                    if (nested != null) {
                        for (j in 0 until nested.length()) {
                            parseSegment(nested.optJSONObject(j))?.let(segments::add)
                        }
                    } else {
                        // Flat shape: also accepted, so the parser does not have to
                        // care which endpoint flavour produced the payload.
                        parseSegment(entry)?.let(segments::add)
                    }
                } catch (_: Exception) {
                    // Skip the unusable entry, keep the usable ones.
                }
            }
        } catch (e: Exception) {
            // Silently ignore parse errors — SponsorBlock is non-critical
        }
        return segments
    }

    private fun parseSegment(obj: JSONObject?): Segment? {
        if (obj == null) return null

        val segmentArray = obj.optJSONArray("segment") ?: return null
        if (segmentArray.length() < 2) return null

        val start = segmentArray.optDouble(0, Double.NaN)
        val end = segmentArray.optDouble(1, Double.NaN)
        if (!start.isFinite() || !end.isFinite() || end <= start) return null

        return Segment(
            start = start,
            end = end,
            category = obj.optString("category", "sponsor"),
            uuid = obj.optString("UUID", ""),
        )
    }

    companion object {
        private const val API_BASE = "https://sponsor.ajay.app"
        private const val HEX_DIGITS = "0123456789abcdef"

        /** The API accepts 4..32 hex characters; 4 is the most private option. */
        private const val HASH_PREFIX_LENGTH = 4

        /**
         * Lowercase hex prefix of `SHA-256(videoId)`, which is what the SponsorBlock
         * lookup path expects. Exposed for tests and for logging without the id.
         */
        fun sha256Prefix(videoId: String, prefixLength: Int = HASH_PREFIX_LENGTH): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(videoId.toByteArray(Charsets.UTF_8))

            val out = StringBuilder(prefixLength)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
                if (out.length >= prefixLength) break
            }
            return out.substring(0, prefixLength).lowercase(Locale.ROOT)
        }

        fun extractVideoId(url: String): String? {
            // Match youtube.com/watch?v=XXXXXXXXXXX
            val watchPattern = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""")
            watchPattern.find(url)?.let { return it.groupValues[1] }

            // Match youtu.be/XXXXXXXXXXX
            val shortPattern = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""")
            shortPattern.find(url)?.let { return it.groupValues[1] }

            // Match youtube.com/embed/XXXXXXXXXXX
            val embedPattern = Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})""")
            embedPattern.find(url)?.let { return it.groupValues[1] }

            // Match youtube.com/shorts/XXXXXXXXXXX
            val shortsPattern = Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})""")
            shortsPattern.find(url)?.let { return it.groupValues[1] }

            return null
        }
    }
}
