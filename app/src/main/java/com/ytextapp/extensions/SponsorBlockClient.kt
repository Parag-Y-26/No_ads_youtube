package com.ytextapp.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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

    suspend fun getSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        try {
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            parseSegments(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseSegments(json: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        try {
            val jsonArray = JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val segmentArray = obj.getJSONArray("segment")
                val start = segmentArray.getDouble(0)
                val end = segmentArray.getDouble(1)
                val category = obj.optString("category", "sponsor")
                val uuid = obj.optString("UUID", "")
                segments.add(Segment(start, end, category, uuid))
            }
        } catch (e: Exception) {
            // Silently ignore parse errors — SponsorBlock is non-critical
        }
        return segments
    }

    companion object {
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
