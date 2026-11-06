package com.streamcontrols.app.twitch

import com.streamcontrols.app.youtube.Channel
import com.streamcontrols.app.youtube.CredentialStore
import com.streamcontrols.app.youtube.Platform
import com.streamcontrols.app.youtube.SavedCredential
import com.streamcontrols.app.youtube.isValidStreamTitle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TwitchApiTest {
    private fun response(chain: okhttp3.Interceptor.Chain, json: String, status: Int = 200): Response =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(json.toResponseBody("application/json".toMediaType())).build()

    @Test fun deviceAuthorizationRejectsOtherHostsAndDoesNotSendSecret() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("id.twitch.tv", chain.request().url.host)
            assertNull(chain.request().header("Authorization"))
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertFalse(body.contains("secret"))
            response(chain, """{"device_code":"fake","user_code":"ABC","verification_uri":"https://evil.example/activate","interval":5,"expires_in":300}""")
        }.build()
        try { TwitchApi("client", client).device(); fail("Untrusted verification URL accepted") }
        catch (_: TwitchResponseInvalid) { }
    }

    @Test fun validatesClientScopeAndChannelIdentity() = runBlocking {
        var validId = "123"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer token", chain.request().header("Authorization"))
            response(chain, """{"client_id":"client","user_id":"$validId","scopes":["channel:manage:broadcast"],"expires_in":300}""")
        }.build()
        val api = TwitchApi("client", client)
        assertEquals("123", api.validate("token", "123"))
        validId = "456"
        try { api.validate("token", "123"); fail("Wrong user accepted") } catch (_: TwitchReconnect) { }
    }

    @Test fun deviceSecretIsNotIncludedInBrowserUrl() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, """{"device_code":"fake-secret","user_code":"ABC123","verification_uri":"https://www.twitch.tv/activate?public=true&device-code=fake-secret","interval":5,"expires_in":300}""")
        }.build()
        val attempt = TwitchApi("client", client).device()
        assertEquals("https://www.twitch.tv/activate?public=true", attempt.verificationUri)
        assertFalse(attempt.verificationUri.contains(attempt.deviceCode))
        assertEquals("ABC123", attempt.userCode)
    }

    @Test fun deviceBrowserUrlIncludesOnlyMatchingUserCode() = runBlocking {
        var parameter = "device-code=ABC123"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, """{"device_code":"private-secret","user_code":"ABC123","verification_uri":"https://www.twitch.tv/activate?public=true&$parameter","interval":5,"expires_in":300}""")
        }.build()
        val api = TwitchApi("client", client)
        assertEquals("https://www.twitch.tv/activate?public=true&device-code=ABC123", api.device().verificationUri)
        parameter = "device-code=ABC123&device-code=private-secret"
        assertEquals("https://www.twitch.tv/activate?public=true", api.device().verificationUri)
        parameter = "device-code=other"
        assertEquals("https://www.twitch.tv/activate?public=true", api.device().verificationUri)
    }

    @Test fun streamAndTitleUpdateAreBoundToRequestedBroadcaster() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("api.twitch.tv", request.url.host)
            assertEquals("client", request.header("Client-Id"))
            assertEquals("Bearer token", request.header("Authorization"))
            val json = when (request.url.encodedPath) {
                "/helix/streams" -> """{"data":[{"id":"987","user_id":"123","type":"live","title":"Old","started_at":"2026-01-01T00:00:00Z","thumbnail_url":"https://static-cdn.jtvnw.net/previews/live-{width}x{height}.jpg"}]}"""
                "/helix/channels" -> {
                    assertEquals("123", request.url.queryParameter("broadcaster_id"))
                    assertEquals("PATCH", request.method)
                    val payload = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    assertEquals(1, payload.length())
                    assertEquals("New title", payload.getString("title"))
                    ""
                }
                else -> error("Unexpected request")
            }
            response(chain, json, if (request.method == "PATCH") 204 else 200)
        }.build()
        val api = TwitchApi("client", client)
        val stream = api.streams("token", "123").single()
        assertEquals("twitch:987", stream.id)
        assertEquals("twitch:123", stream.channelId)
        assertEquals(Platform.TWITCH, stream.platform)
        assertEquals("https://static-cdn.jtvnw.net/previews/live-320x180.jpg", stream.thumbnail)
        api.updateTitle("token", "123", "New title")
    }

    @Test fun rejectsWrongStreamIdentity() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain, """{"data":[{"id":"987","user_id":"456","type":"live","title":"Wrong"}]}""")
        }.build()
        try { TwitchApi("client", client).streams("token", "123"); fail("Wrong channel accepted") }
        catch (_: TwitchResponseInvalid) { }
    }

    private class FakeVault : CredentialStore {
        var saved: SavedCredential? = SavedCredential("old-refresh", Channel("twitch:123", "Test", platform = Platform.TWITCH), null)
        override fun persist(saved: SavedCredential) { this.saved = saved }
        override fun read() = saved
        override fun clear() { saved = null }
    }

    @Test fun refreshPersistsRotatedTokenAndMarksOldTokenConsumedBeforeRequest() = runBlocking {
        val vault = FakeVault()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().url.encodedPath) {
                "/oauth2/token" -> {
                    calls.incrementAndGet()
                    assertEquals("", vault.saved?.refresh)
                    response(chain, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","scope":["channel:manage:broadcast"]}""")
                }
                "/oauth2/validate" -> response(chain, """{"client_id":"client","user_id":"123","scopes":["channel:manage:broadcast"],"expires_in":3600}""")
                else -> error("Unexpected request")
            }
        }.build()
        val session = TwitchSessionManager(vault, TwitchApi("client", client))
        assertEquals("twitch:123", session.restore()?.id)
        assertEquals("new-access", session.token())
        assertEquals("new-refresh", vault.saved?.refresh)
        assertEquals(1, calls.get())
    }

    @Test fun uncertainRefreshNeverRetriesOldToken() = runBlocking {
        val vault = FakeVault()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("connection lost") }.build()
        val session = TwitchSessionManager(vault, TwitchApi("client", client))
        session.restore()
        repeat(2) { try { session.token(); fail("Expected reconnect") } catch (_: TwitchReconnect) { } }
        assertEquals(1, calls.get())
        assertEquals("", vault.saved?.refresh)
    }

    @Test fun disconnectDuringRefreshRevokesTheNewAccessToken() = runBlocking {
        val vault = FakeVault()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            when (chain.request().url.encodedPath) {
                "/oauth2/token" -> {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    response(chain, """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","scope":["channel:manage:broadcast"]}""")
                }
                "/oauth2/revoke" -> {
                    val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                    assertTrue(body.contains("token=new-access"))
                    assertFalse(body.contains("new-refresh"))
                    response(chain, "")
                }
                else -> error("Unexpected request")
            }
        }.build()
        val session = TwitchSessionManager(vault, TwitchApi("client", client))
        session.restore()
        val result = async { try { session.token(); false } catch (_: TwitchReconnect) { true } }
        assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
        session.invalidate()
        release.countDown()
        assertTrue(result.await())
        assertTrue(session.disconnect())
        assertNull(vault.saved)
    }

    @Test fun titleLimitsArePlatformSpecific() {
        assertTrue(isValidStreamTitle("x".repeat(140), Platform.TWITCH))
        assertFalse(isValidStreamTitle("x".repeat(141), Platform.TWITCH))
        assertFalse(isValidStreamTitle("x".repeat(101), Platform.YOUTUBE))
    }

    @Test fun invalidTitleIsRejectedBeforeSendingARequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Invalid title reached network") }.build()
        try { TwitchApi("client", client).updateTitle("token", "123", "x".repeat(141)); fail("Invalid title accepted") }
        catch (_: IllegalArgumentException) { }
    }

}
