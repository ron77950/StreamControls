package com.streamcontrols.app.youtube

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamcontrols.app.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class BrowserStreamIntegrationTest {
    @Test fun mismatchedChannelDoesNotBlockOtherChannelsOrExposeItsStreams() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = AppPreferences(context)
        preferences.clearSession()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val id = if (request.header("Authorization") == "Bearer token-a") "a" else "unexpected"
            val json = when (request.url.encodedPath) {
                "/youtube/v3/channels" -> """{"items":[{"id":"$id","snippet":{"title":"Channel $id"}}]}"""
                "/youtube/v3/liveBroadcasts" -> {
                    check(id == "a")
                    """{"items":[{"id":"live-a","snippet":{"channelId":"a","title":"Stream a"},"status":{"lifeCycleStatus":"live"}}]}"""
                }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = object : ChannelSessions {
            val channels = listOf(Channel("a", "Channel a"), Channel("b", "Channel b"))
            override suspend fun restoreAll() = channels
            override fun channel(id: String) = channels.find { it.id == id }
            override fun lastConnected(): Channel? = null
            override suspend fun token(id: String) = "token-$id"
            override fun invalidate(id: String) = Unit
            override suspend fun disconnect(id: String) = true
        }
        val model = withContext(Dispatchers.Main) { StreamsViewModel(YouTubeApi(client), preferences, session) }
        try {
            val loaded = withTimeout(10_000) { model.state.first { it.loaded && !it.busy } }
            assertEquals(listOf("a"), loaded.streams.map { it.channelId })
            assertEquals(setOf("b"), loaded.reconnectIds)
        } finally { preferences.clearSession() }
    }

    @Test fun multipleChannelsUseTheirOwnCredentialsAndDisconnectIndependently() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = AppPreferences(context)
        preferences.clearSession()
        val ended = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val id = when (request.header("Authorization")) {
                "Bearer token-a" -> "a"
                "Bearer token-b" -> "b"
                else -> error("Unexpected credential")
            }
            val json = when (request.url.encodedPath) {
                "/youtube/v3/channels" -> """{"items":[{"id":"$id","snippet":{"title":"Channel $id"}}]}"""
                "/youtube/v3/liveBroadcasts" -> {
                    val status = if (id == "b" && ended.get() > 0) "complete" else "live"
                    """{"items":[{"id":"live-$id","snippet":{"channelId":"$id","title":"Stream $id"},"status":{"lifeCycleStatus":"$status"}}]}"""
                }
                "/youtube/v3/liveBroadcasts/transition" -> {
                    check(id == "b" && request.url.queryParameter("id") == "live-b")
                    ended.incrementAndGet()
                    "{}"
                }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = object : ChannelSessions {
            val active = mutableMapOf("a" to Channel("a", "Channel a"), "b" to Channel("b", "Channel b"))
            override suspend fun restoreAll() = active.values.toList()
            override fun channel(id: String) = active[id]
            override fun lastConnected(): Channel? = null
            override suspend fun token(id: String) = if (active.containsKey(id)) "token-$id" else throw AuthorizationRequired()
            override fun invalidate(id: String) { active.remove(id) }
            override suspend fun disconnect(id: String) = true
        }
        val model = withContext(Dispatchers.Main) { StreamsViewModel(YouTubeApi(client), preferences, session) }
        try {
            val loaded = withTimeout(10_000) { model.state.first { it.loaded && !it.busy } }
            assertEquals(setOf("a", "b"), loaded.streams.map { it.channelId }.toSet())
            withContext(Dispatchers.Main) {
                model.select(loaded.streams.single { it.channelId == "b" })
                model.endSelectedStream("live-b")
            }
            val afterEnd = withTimeout(10_000) { model.state.first { it.message == "Livestream ended." && !it.busy } }
            assertEquals(listOf("a"), afterEnd.streams.map { it.channelId })
            assertEquals("Livestream ended.", afterEnd.messageFor(MessageScreen.MANAGE))
            assertNull(afterEnd.messageFor(MessageScreen.STREAMS))
            withContext(Dispatchers.Main) { model.disconnect("a") }
            val afterDisconnect = withTimeout(10_000) { model.state.first { !it.busy && it.channels.size == 1 } }
            assertEquals("b", afterDisconnect.channels.single().id)
            assertEquals("YouTube access revoked. Other channels may need to reconnect.", afterDisconnect.messageFor(MessageScreen.ACCOUNTS))
            assertNull(afterDisconnect.messageFor(MessageScreen.STREAMS))
            assertEquals(1, ended.get())
        } finally { preferences.clearSession() }
    }

    @Test fun uncertainEndIsReadBackAndNeverRetried() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = AppPreferences(context)
        preferences.clearSession()
        val transitions = AtomicInteger()
        val statusReads = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val json = when (request.url.encodedPath) {
                "/youtube/v3/channels" -> """{"items":[{"id":"brand","snippet":{"title":"Brand"}}]}"""
                "/youtube/v3/liveBroadcasts" -> {
                    if (request.url.queryParameter("id") != null) statusReads.incrementAndGet()
                    """{"items":[{"id":"live","snippet":{"channelId":"brand","title":"Stream","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/live/default.jpg"}}},"status":{"lifeCycleStatus":"live"}}]}"""
                }
                "/youtube/v3/liveBroadcasts/transition" -> { transitions.incrementAndGet(); throw java.io.IOException("simulated timeout") }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = object : ChannelSessions {
            private var active: Channel? = null
            override suspend fun restoreAll(): List<Channel> = emptyList()
            override fun channel(id: String) = active?.takeIf { it.id == id }
            override fun lastConnected() = active
            override suspend fun token(id: String) = "fake-token"
            override fun invalidate(id: String) { active = null }
            override suspend fun disconnect(id: String) = true
            fun activate() { active = Channel("brand", "Brand") }
        }
        val model = withContext(Dispatchers.Main) { StreamsViewModel(YouTubeApi(client), preferences, session) }
        try {
            withTimeout(10_000) { model.state.first { !it.busy } }
            withContext(Dispatchers.Main) { session.activate(); model.connectBrowser() }
            withTimeout(10_000) { model.state.first { it.loaded && !it.busy } }
            withContext(Dispatchers.Main) { model.endSelectedStream("live") }
            val result = withTimeout(10_000) { model.state.first { !it.busy && it.message?.contains("not confirmed") == true } }
            assertEquals(1, transitions.get())
            assertEquals(2, statusReads.get())
            assertEquals("live", result.selectedId)
        } finally { preferences.clearSession() }
    }

    @Test fun endStreamChecksIdentityAndDoesNotSubmitTwice() = runBlocking {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = AppPreferences(context)
        preferences.clearSession()
        val status = AtomicReference("live")
        val writes = AtomicInteger()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host == "www.googleapis.com" && request.header("Authorization") == "Bearer fake-token")
            val json = when (request.url.encodedPath) {
                "/youtube/v3/channels" -> """{"items":[{"id":"brand","snippet":{"title":"Brand"}}]}"""
                "/youtube/v3/liveBroadcasts" -> {
                    val items = if (status.get() == "complete" && request.url.queryParameter("id") == null) "" else
                        """{"id":"live","snippet":{"channelId":"brand","title":"Stream","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/live/default.jpg"}}},"status":{"lifeCycleStatus":"${status.get()}"}}"""
                    """{"items":[$items]}"""
                }
                "/youtube/v3/liveBroadcasts/transition" -> {
                    check(request.method == "POST" && request.url.queryParameter("id") == "live")
                    check(request.url.queryParameter("broadcastStatus") == "complete")
                    writes.incrementAndGet(); writeStarted.countDown()
                    check(releaseWrite.await(10, TimeUnit.SECONDS))
                    status.set("complete")
                    "{}"
                }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = object : ChannelSessions {
            private var active: Channel? = null
            override suspend fun restoreAll(): List<Channel> = emptyList()
            override fun channel(id: String) = active?.takeIf { it.id == id }
            override fun lastConnected() = active
            override suspend fun token(id: String) = "fake-token"
            override fun invalidate(id: String) { active = null }
            override suspend fun disconnect(id: String) = true
            fun activate() { active = Channel("brand", "Brand") }
        }
        val model = withContext(Dispatchers.Main) { StreamsViewModel(YouTubeApi(client), preferences, session) }
        try {
            withTimeout(10_000) { model.state.first { !it.busy } }
            withContext(Dispatchers.Main) { session.activate(); model.connectBrowser() }
            withTimeout(10_000) { model.state.first { it.loaded && !it.busy } }
            withContext(Dispatchers.Main) { model.endSelectedStream("different-broadcast") }
            assertEquals(0, writes.get())
            withContext(Dispatchers.Main) { model.endSelectedStream("live") }
            assertTrue(withContext(Dispatchers.IO) { writeStarted.await(10, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { model.endSelectedStream("live") }
            releaseWrite.countDown()
            val result = withTimeout(10_000) { model.state.first { it.message == "Livestream ended." && !it.busy } }
            assertEquals(1, writes.get())
            assertTrue(result.streams.isEmpty())
            assertNull(result.selectedId)
        } finally { releaseWrite.countDown(); preferences.clearSession() }
    }

    @Test fun confirmedBrowserChannelCanFindAndUpdateOnlyItsOwnStream() = runBlocking {
        // The instrumentation package has its own isolated DataStore; never clear the user's session.
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getApplicationContext(): Context = this
        }
        val preferences = AppPreferences(context)
        preferences.clearSession()
        val title = AtomicReference("Original")
        val writes = AtomicInteger()
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            check(request.url.scheme == "https" && request.url.host == "www.googleapis.com")
            check(request.header("Authorization") == "Bearer fake-browser-token")
            val response = when (request.url.encodedPath) {
                "/youtube/v3/channels" -> """{"items":[{"id":"brand","snippet":{"title":"Brand channel"}}]}"""
                "/youtube/v3/liveBroadcasts" -> {
                    check(request.url.queryParameter("broadcastStatus") == "active")
                    check(request.url.queryParameter("broadcastType") == "all")
                    """{"items":[{"id":"live","snippet":{"channelId":"brand","title":"${title.get()}","thumbnails":{"default":{"url":"https://i.ytimg.com/vi/live/default.jpg"}}},"status":{"lifeCycleStatus":"live"}}]}"""
                }
                "/youtube/v3/videos" -> if (request.method == "PUT") {
                    writes.incrementAndGet()
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val payload = JSONObject(buffer.readUtf8())
                    check(payload.getString("id") == "live")
                    val snippet = payload.getJSONObject("snippet")
                    check(snippet.getString("description") == "Keep description")
                    check(snippet.getString("categoryId") == "20")
                    check(snippet.getJSONArray("tags").getString(0) == "keep")
                    check(request.header("If-Match") == "etag")
                    writeStarted.countDown()
                    check(releaseWrite.await(10, TimeUnit.SECONDS))
                    title.set(snippet.getString("title"))
                    "{}"
                } else """{"items":[{"id":"live","etag":"etag","snippet":{"channelId":"brand","title":"${title.get()}","categoryId":"20","description":"Keep description","tags":["keep"]}}]}"""
                else -> error("Unexpected fake endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val fakeSession = object : ChannelSessions {
            private var selected: Channel? = null
            override suspend fun restoreAll(): List<Channel> = emptyList()
            override fun channel(id: String): Channel? = selected?.takeIf { it.id == id }
            override fun lastConnected(): Channel? = selected
            override suspend fun token(id: String): String = "fake-browser-token"
            override fun invalidate(id: String) { selected = null }
            override suspend fun disconnect(id: String): Boolean = true
            fun activate() { selected = Channel("brand", "Brand channel") }
        }
        val model = withContext(Dispatchers.Main) { StreamsViewModel(YouTubeApi(client), preferences, fakeSession) }
        try {
            withTimeout(10_000) { model.state.first { !it.busy } }
            withContext(Dispatchers.Main) {
                fakeSession.activate()
                model.connectBrowser()
            }
            val loaded = withTimeout(10_000) { model.state.first { it.loaded && !it.busy } }
            assertEquals("brand", loaded.channels.singleOrNull()?.id)
            assertEquals("live", loaded.selectedId)
            assertEquals("Original", loaded.draft)

            withContext(Dispatchers.Main) { model.edit("Updated test title"); model.updateTitle() }
            assertTrue(withContext(Dispatchers.IO) { writeStarted.await(10, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { model.updateTitle() }
            releaseWrite.countDown()
            val updated = withTimeout(10_000) { model.state.first { it.message == "Title updated." && !it.busy } }
            assertEquals("Updated test title", updated.streams.single().title)
            assertEquals(1, writes.get())
        } finally {
            releaseWrite.countDown()
            fakeSession.invalidate("brand")
            preferences.clearSession()
        }
    }
}
