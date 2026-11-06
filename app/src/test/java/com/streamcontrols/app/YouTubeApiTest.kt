package com.streamcontrols.app

import com.streamcontrols.app.youtube.IncompleteResponse
import com.streamcontrols.app.youtube.NoYouTubeChannel
import com.streamcontrols.app.youtube.MultipleYouTubeChannels
import com.streamcontrols.app.youtube.StreamVisibility
import com.streamcontrols.app.youtube.VideoSnapshot
import com.streamcontrols.app.youtube.YouTubeApi
import com.streamcontrols.app.youtube.YouTubeFailure
import com.streamcontrols.app.youtube.youtubeFailureMessage
import com.streamcontrols.app.youtube.CHANNEL_REQUIRED_MESSAGE
import com.streamcontrols.app.youtube.writableSnippet
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class YouTubeApiTest {
    private val fixture = JSONObject("""{"title":"old","categoryId":"20","description":"keep this","tags":["one","two"],"defaultLanguage":"en","channelId":"channel","thumbnails":{"default":{}},"publishedAt":"readonly"}""")

    @Test fun serializedUpdatePreservesWritableFieldsOnly() = runBlocking {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val snapshot = VideoSnapshot("video", "channel", "old", "\"etag\"", writableSnippet(fixture))
        YouTubeApi(client).update("fake-token", snapshot, "new title")
        val request = captured.get()
        assertEquals("www.googleapis.com", request.url.host)
        assertEquals("https", request.url.scheme)
        assertEquals("PUT", request.method)
        assertEquals("snippet", request.url.queryParameter("part"))
        assertEquals("Bearer fake-token", request.header("Authorization"))
        assertEquals("\"etag\"", request.header("If-Match"))
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        val snippet = payload.getJSONObject("snippet")
        assertEquals("video", payload.getString("id"))
        assertEquals("new title", snippet.getString("title"))
        assertEquals("20", snippet.getString("categoryId"))
        assertEquals("keep this", snippet.getString("description"))
        assertEquals("two", snippet.getJSONArray("tags").getString(1))
        assertEquals("en", snippet.getString("defaultLanguage"))
        assertFalse(snippet.has("channelId"))
        assertFalse(snippet.has("thumbnails"))
        assertFalse(payload.has("status"))
    }

    @Test fun missingCategoryFailsClosed() {
        try { writableSnippet(JSONObject("""{"title":"old"}""")); fail("Expected incomplete metadata") }
        catch (_: IncompleteResponse) { }
    }

    @Test fun titleConfirmationNeedsOnlyVideoIdentityAndTitle() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("items(id,snippet(channelId,title))", chain.request().url.queryParameter("fields"))
            val json = """{"items":[{"id":"video","snippet":{"channelId":"channel","title":"new"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val result = YouTubeApi(client).videoTitle("fake-token", "video")
        assertEquals("video", result.id)
        assertEquals("channel", result.channelId)
        assertEquals("new", result.title)
    }

    @Test fun redirectIsRejectedWithoutSendingTokenElsewhere() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://untrusted.example/steal")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = YouTubeApi(client)
        assertFalse(api.client.followRedirects)
        assertFalse(api.client.followSslRedirects)
        try { api.channel("fake-token"); fail("Expected redirect to fail") }
        catch (e: YouTubeFailure) { assertEquals(302, e.status) }
        assertEquals(1, calls.get())
    }

    @Test fun activeBroadcastsFollowPaginationAndKeepChannelIsolation() = runBlocking {
        val seen = mutableListOf<String?>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("active", request.url.queryParameter("broadcastStatus"))
            assertEquals("all", request.url.queryParameter("broadcastType"))
            val page = request.url.queryParameter("pageToken")
            seen.add(page)
            val json = if (page == null) """{"items":[{"id":"one","snippet":{"channelId":"channel","title":"First","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/one/default.jpg"}}},"status":{"lifeCycleStatus":"live"}}],"nextPageToken":"next"}"""
                else """{"items":[{"id":"two","snippet":{"channelId":"channel","title":"Second","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/two/default.jpg"}}},"status":{"lifeCycleStatus":"live"}}]}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val streams = YouTubeApi(client).streams("fake-token", "channel")
        assertEquals(listOf("one", "two"), streams.map { it.id })
        assertEquals(listOf("channel", "channel"), streams.map { it.channelId })
        assertEquals(listOf(null, "next"), seen)
    }

    @Test fun wrongChannelBroadcastFailsClosed() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"items":[{"id":"one","snippet":{"channelId":"other","title":"First"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { YouTubeApi(client).streams("fake-token", "channel"); fail("Expected channel mismatch") }
        catch (_: IncompleteResponse) { }
    }

    @Test fun broadcastsUseAvailableThumbnailSizesWithoutExtraApiCalls() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val json = """{"items":[{"id":"one","snippet":{"channelId":"channel","title":"Live","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/one/default.jpg"},"high":{"url":"https://i.ytimg.com/vi/one/hqdefault.jpg"}}},"status":{"lifeCycleStatus":"live"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val stream = YouTubeApi(client).streams("fake-token", "channel").single()
        assertEquals("https://i.ytimg.com/vi/one/hqdefault.jpg", stream.thumbnail)
        assertEquals(1, calls.get())
    }

    @Test fun missingBroadcastThumbnailDoesNotCauseAnExtraApiRequest() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("/youtube/v3/liveBroadcasts", chain.request().url.encodedPath)
            val json = """{"items":[{"id":"one","snippet":{"channelId":"channel","title":"Live"},"status":{"lifeCycleStatus":"live"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val stream = YouTubeApi(client).streams("fake-token", "channel").single()
        assertNull(stream.thumbnail)
        assertEquals(1, calls.get())
    }

    @Test fun writeTransportFailureIsNotAutomaticallyRetried() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().retryOnConnectionFailure(true).addInterceptor {
            calls.incrementAndGet(); throw java.io.IOException("simulated failure")
        }.build()
        val snapshot = VideoSnapshot("video", "channel", "old", "etag", writableSnippet(fixture))
        val api = YouTubeApi(client)
        assertFalse(api.client.retryOnConnectionFailure)
        try { api.update("fake-token", snapshot, "new"); fail("Expected failure") }
        catch (_: java.io.IOException) { }
        assertEquals(1, calls.get())
    }

    @Test fun rejectedAuthorizationReturnsSanitizedError() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"error":{"errors":[{"reason":"authError","message":"sensitive details"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { YouTubeApi(client).channel("fake-token"); fail("Expected error") }
        catch (e: YouTubeFailure) {
            assertEquals(401, e.status)
            assertEquals("authError", e.reason)
            assertFalse(e.toString().contains("sensitive details"))
            assertFalse(e.toString().contains("fake-token"))
        }
    }

    private fun channelApi(json: String): YouTubeApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return YouTubeApi(client)
    }

    @Test fun emptyChannelResponseIsIdentified() = runBlocking {
        for (json in listOf("""{"pageInfo":{"totalResults":0}}""", """{"items":[]}""")) {
            try { channelApi(json).channel("fake-token"); fail("Expected no channel") }
            catch (_: NoYouTubeChannel) { }
        }
    }

    @Test fun multipleChannelsAreNotSelectedArbitrarily() = runBlocking {
        val json = """{"items":[{"id":"a","snippet":{"title":"A"}},{"id":"b","snippet":{"title":"B"}}]}"""
        try { channelApi(json).channel("fake-token"); fail("Expected multiple channels") }
        catch (_: MultipleYouTubeChannels) { }
    }

    @Test fun validSingleChannelIsReturned() = runBlocking {
        val json = """{"items":[{"id":"a","snippet":{"title":"Example"}}]}"""
        val channel = channelApi(json).channel("fake-token")
        assertEquals("a", channel.id)
        assertEquals("Example", channel.name)
    }

    @Test fun channelImageUsesChannelSnippetWithoutAdditionalRequest() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val json = """{"items":[{"id":"a","snippet":{"title":"Example","thumbnails":{"default":{"url":"https://yt3.googleusercontent.com/avatar"}}}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals("https://yt3.googleusercontent.com/avatar", YouTubeApi(client).channel("fake-token").imageUrl)
        assertEquals(1, calls.get())
    }

    @Test fun invalidChannelListDoesNotMasqueradeAsNoChannel() = runBlocking {
        for (json in listOf("{}", """{"items":"invalid"}""", """{"items":[{"snippet":{"title":"No ID"}}]}""", """{"items":[{"id":"a"}]}""")) {
            try { channelApi(json).channel("fake-token"); fail("Expected incomplete response") }
            catch (_: IncompleteResponse) { }
        }
    }

    @Test fun channelNotLinkedErrorIsNotReportedAsExpiredAuthorization() {
        assertEquals(CHANNEL_REQUIRED_MESSAGE, youtubeFailureMessage(YouTubeFailure(401, "youtubeSignupRequired")))
        assertTrue(youtubeFailureMessage(YouTubeFailure(401, "authError")).contains("expired"))
    }

    @Test fun liveApiErrorsExplainDifferentFailureReasons() {
        assertTrue(youtubeFailureMessage(YouTubeFailure(403, "liveStreamingNotEnabled")).contains("not enabled"))
        assertTrue(youtubeFailureMessage(YouTubeFailure(403, "insufficientLivePermissions")).contains("live broadcasts"))
        assertTrue(youtubeFailureMessage(YouTubeFailure(403, "quotaExceeded")).contains("quota"))
        assertTrue(youtubeFailureMessage(YouTubeFailure(403, "userRequestsExceedRateLimit")).contains("too many requests"))
    }

    @Test fun channelLookupPreservesSafeYouTubeErrorReason() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"error":{"errors":[{"reason":"youtubeSignupRequired","message":"private response"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { YouTubeApi(client).channel("fake-token"); fail("Expected API error") }
        catch (e: YouTubeFailure) {
            assertEquals(CHANNEL_REQUIRED_MESSAGE, youtubeFailureMessage(e))
            assertFalse(e.toString().contains("private response"))
        }
    }

    @Test fun transitionUsesExactBroadcastAndNoRequestBody() = runBlocking {
        val captured = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured.set(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build()
        YouTubeApi(client).endBroadcast("fake-token", "chosen-id")
        val request = captured.get()
        assertEquals("POST", request.method)
        assertEquals("/youtube/v3/liveBroadcasts/transition", request.url.encodedPath)
        assertEquals("complete", request.url.queryParameter("broadcastStatus"))
        assertEquals("chosen-id", request.url.queryParameter("id"))
        assertEquals(0L, request.body!!.contentLength())
    }

    @Test fun discoveryReadsVisibilityWithoutAdditionalRequests() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val json = """{"items":[{"id":"a","snippet":{"channelId":"c","title":"A"},"status":{"lifeCycleStatus":"live","privacyStatus":"public"}},{"id":"b","snippet":{"channelId":"c","title":"B"},"status":{"lifeCycleStatus":"live","privacyStatus":"private"}},{"id":"c","snippet":{"channelId":"c","title":"C"},"status":{"lifeCycleStatus":"live","privacyStatus":"unlisted"}},{"id":"d","snippet":{"channelId":"c","title":"D"},"status":{"lifeCycleStatus":"live"}},{"id":"e","snippet":{"channelId":"c","title":"E"},"status":{"lifeCycleStatus":"live","privacyStatus":"unexpected"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(listOf(StreamVisibility.PUBLIC, StreamVisibility.PRIVATE, StreamVisibility.UNLISTED, StreamVisibility.UNKNOWN, StreamVisibility.UNKNOWN),
            YouTubeApi(client).streams("fake-token", "c").map { it.visibility })
        assertEquals(1, calls.get())
    }

    @Test fun discoveryExcludesNonLiveLifecycleStates() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"items":[{"id":"a","snippet":{"channelId":"c","title":"Live"},"status":{"lifeCycleStatus":"live"}},{"id":"b","snippet":{"channelId":"c","title":"Testing"},"status":{"lifeCycleStatus":"testing"}},{"id":"c","snippet":{"channelId":"c","title":"Ended"},"status":{"lifeCycleStatus":"complete"}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertEquals(listOf("a"), YouTubeApi(client).streams("fake-token", "c").map { it.id })
    }

    @Test fun noActiveStreamsMayOmitTheItemsArray() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"pageInfo":{"totalResults":0}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        assertTrue(YouTubeApi(client).streams("fake-token", "channel").isEmpty())
    }
}
