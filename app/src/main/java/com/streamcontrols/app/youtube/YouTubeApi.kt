package com.streamcontrols.app.youtube

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class Platform { YOUTUBE, TWITCH }
data class Channel(val id: String, val name: String, val imageUrl: String? = null, val platform: Platform = Platform.YOUTUBE)
enum class StreamVisibility { PUBLIC, PRIVATE, UNLISTED, UNKNOWN }
data class LiveStream(val id: String, val title: String, val thumbnail: String?, val startedAt: String?, val visibility: StreamVisibility = StreamVisibility.UNKNOWN, val channelId: String = "", val platform: Platform = Platform.YOUTUBE)
data class BroadcastStatus(val id: String, val channelId: String, val lifecycle: String)
data class VideoSnapshot(val id: String, val channelId: String, val title: String, val etag: String, val writable: JSONObject)
data class VideoTitle(val id: String, val channelId: String, val title: String)

class YouTubeFailure(val status: Int, val reason: String = "") : Exception()
class IncompleteResponse : Exception()
class NoYouTubeChannel : Exception()
class MultipleYouTubeChannels : Exception()

/** Authenticated requests can only go to this fixed HTTPS API origin. Redirects are disabled. */
class YouTubeApi(client: OkHttpClient = OkHttpClient()) {
    internal val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val base = "https://www.googleapis.com/youtube/v3/".toHttpUrl()

    private suspend fun request(token: String, path: String, params: Map<String, String>, body: JSONObject? = null, etag: String? = null, post: Boolean = false): JSONObject {
        require(path in setOf("channels", "liveBroadcasts", "liveBroadcasts/transition", "videos"))
        val url = base.newBuilder().apply { path.split('/').forEach(::addPathSegment); params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        check(url.isHttps && url.host == "www.googleapis.com")
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token")
        if (etag != null) builder.header("If-Match", etag)
        if (body != null) builder.put(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (post) builder.post(ByteArray(0).toRequestBody(null))
        val call = client.newCall(builder.build())
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
        }
        response.use {
            val content = it.body?.string() ?: throw IncompleteResponse()
            if (!it.isSuccessful) {
                val reason = try { JSONObject(content).getJSONObject("error").getJSONArray("errors").getJSONObject(0).optString("reason") } catch (_: Exception) { "" }
                AuthTrace.apiFailure(path, it.code, reason)
                if (path == "channels") AuthTrace.channelResponse(it.code, reason, null)
                throw YouTubeFailure(it.code, reason)
            }
            val json = try { JSONObject(content) } catch (_: Exception) { throw IncompleteResponse() }
            if (path == "channels") AuthTrace.channelResponse(it.code, null, json.optJSONArray("items")?.length())
            return json
        }
    }

    suspend fun channel(token: String): Channel {
        val result = request(token, "channels", mapOf("part" to "snippet", "mine" to "true", "maxResults" to "50"))
        // Treat an omitted list as empty only when YouTube explicitly reports zero results.
        val items = result.optJSONArray("items")
        if (items == null) {
            if (!result.has("items") && result.optJSONObject("pageInfo")?.optInt("totalResults", -1) == 0) throw NoYouTubeChannel()
            throw IncompleteResponse()
        }
        if (items.length() == 0) throw NoYouTubeChannel()
        val hasNextPage = result.has("nextPageToken") && !result.isNull("nextPageToken") && result.optString("nextPageToken").isNotBlank()
        if (hasNextPage || items.length() > 1) throw MultipleYouTubeChannels()
        try {
            val item = items.getJSONObject(0)
            val id = item.getString("id").takeIf { it.isNotBlank() } ?: throw IncompleteResponse()
            val snippet = item.getJSONObject("snippet")
            val name = snippet.getString("title").takeIf { it.isNotBlank() } ?: throw IncompleteResponse()
            return Channel(id, name, channelImageCandidate(snippet.optJSONObject("thumbnails")))
        } catch (_: org.json.JSONException) {
            throw IncompleteResponse()
        }
    }

    suspend fun streams(token: String, channelId: String): List<LiveStream> {
        val streams = mutableListOf<LiveStream>()
        val seenPages = mutableSetOf<String>()
        var page: String? = null
        do {
            val params = mutableMapOf("part" to "snippet,status", "broadcastStatus" to "active", "broadcastType" to "all", "maxResults" to "50")
            page?.let { params["pageToken"] = it }
            val result = request(token, "liveBroadcasts", params)
            val items = result.optJSONArray("items") ?: if (!result.has("items") && result.optJSONObject("pageInfo")?.optInt("totalResults", -1) == 0) org.json.JSONArray() else throw IncompleteResponse()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.getJSONObject("snippet")
                if (snippet.getString("channelId") != channelId) throw IncompleteResponse()
                val status = item.getJSONObject("status")
                if (status.getString("lifeCycleStatus") != "live") continue
                val thumbnail = thumbnailCandidate(snippet.optJSONObject("thumbnails"))
                val visibility = when (status.optString("privacyStatus")) {
                    "public" -> StreamVisibility.PUBLIC
                    "private" -> StreamVisibility.PRIVATE
                    "unlisted" -> StreamVisibility.UNLISTED
                    else -> StreamVisibility.UNKNOWN
                }
                streams.add(LiveStream(item.getString("id"), snippet.getString("title"), thumbnail, snippet.optString("actualStartTime").takeIf { it.isNotBlank() }, visibility, channelId))
            }
            page = result.optString("nextPageToken").takeIf { it.isNotBlank() }
            if (page != null && !seenPages.add(page)) throw IncompleteResponse()
        } while (page != null)
        return streams
    }

    suspend fun broadcast(token: String, id: String): BroadcastStatus {
        val items = request(token, "liveBroadcasts", mapOf("part" to "snippet,status", "id" to id)).optJSONArray("items") ?: throw IncompleteResponse()
        if (items.length() != 1) throw IncompleteResponse()
        val item = items.getJSONObject(0)
        return BroadcastStatus(item.getString("id"), item.getJSONObject("snippet").getString("channelId"), item.getJSONObject("status").getString("lifeCycleStatus"))
    }

    suspend fun endBroadcast(token: String, id: String) {
        request(token, "liveBroadcasts/transition", mapOf("part" to "id,status", "id" to id, "broadcastStatus" to "complete"), post = true)
    }

    suspend fun video(token: String, id: String): VideoSnapshot {
        val items = request(token, "videos", mapOf("part" to "snippet", "id" to id)).getJSONArray("items")
        if (items.length() != 1) throw IncompleteResponse()
        val item = items.getJSONObject(0)
        val snippet = item.getJSONObject("snippet")
        val writable = writableSnippet(snippet)
        val etag = item.optString("etag").takeIf { it.isNotBlank() } ?: throw IncompleteResponse()
        return VideoSnapshot(item.getString("id"), snippet.getString("channelId"), snippet.getString("title"), etag, writable)
    }

    /** Confirmation only needs identity and title, not an ETag or writable metadata. */
    suspend fun videoTitle(token: String, id: String): VideoTitle {
        val result = request(token, "videos", mapOf("part" to "snippet", "id" to id, "fields" to "items(id,snippet(channelId,title))"))
        val items = result.optJSONArray("items") ?: throw IncompleteResponse()
        if (items.length() != 1) throw IncompleteResponse()
        try {
            val item = items.getJSONObject(0)
            val snippet = item.getJSONObject("snippet")
            val videoId = item.getString("id").takeIf { it.isNotBlank() } ?: throw IncompleteResponse()
            val channelId = snippet.getString("channelId").takeIf { it.isNotBlank() } ?: throw IncompleteResponse()
            return VideoTitle(videoId, channelId, snippet.getString("title"))
        } catch (_: org.json.JSONException) { throw IncompleteResponse() }
    }

    suspend fun update(token: String, snapshot: VideoSnapshot, title: String) {
        require(isValidStreamTitle(title, Platform.YOUTUBE)) { "Invalid YouTube title" }
        request(token, "videos", mapOf("part" to "snippet"), updateBody(snapshot, title), snapshot.etag)
    }
}

/** Build an allowlisted write payload; absent optional fields remain absent. */
fun writableSnippet(source: JSONObject): JSONObject {
    if (!source.has("title") || source.isNull("title") || !source.has("description") || source.isNull("description") || !source.has("categoryId") || source.isNull("categoryId") || source.getString("categoryId").isBlank()) throw IncompleteResponse()
    return JSONObject().apply {
        listOf("title", "categoryId", "description", "tags", "defaultLanguage").forEach { key ->
            if (source.has(key) && !source.isNull(key)) put(key, source.get(key))
        }
    }
}

fun updateBody(snapshot: VideoSnapshot, title: String): JSONObject {
    val snippet = JSONObject(snapshot.writable.toString()).put("title", title)
    return JSONObject().put("id", snapshot.id).put("snippet", snippet)
}
