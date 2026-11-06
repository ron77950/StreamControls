package com.streamcontrols.app.youtube

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BrowserSessionManagerTest {
    private class FakeStore : CredentialStore {
        var saved: SavedCredential? = SavedCredential("fake-old", Channel("brand", "Brand"), "test-key")
        override fun persist(saved: SavedCredential) { this.saved = saved }
        override fun read() = saved
        override fun clear() { saved = null }
    }

    private class FakeKeys : DpopKeys {
        override fun create() = "test-key"
        override fun exists(alias: String) = alias == "test-key"
        override fun proof(alias: String, code: String?, nonce: String?) = "test-proof"
        override fun delete(alias: String) = Unit
    }

    @Test fun oldUnboundCredentialsRequireReconnection() = runBlocking {
        val store = FakeStore().apply { saved = saved!!.copy(keyAlias = null) }
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("Unexpected request") }.build()
        val session = BrowserSessionManager(store, "fake-client", client, FakeKeys())
        assertEquals("brand", session.restore()?.id)
        try { session.token(); fail("Unbound credential was accepted") } catch (_: AuthorizationRequired) { }
        assertEquals("fake-old", store.saved?.refresh)
        assertEquals(0, calls.get())
    }

    @Test fun missingDpopKeyRequiresReconnectionWithoutSendingToken() = runBlocking {
        val store = FakeStore().apply { saved = saved!!.copy(keyAlias = "missing-key") }
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("Unexpected request") }.build()
        val session = BrowserSessionManager(store, "fake-client", client, FakeKeys())
        session.restore()
        try { session.token(); fail("Credential was sent without its key") } catch (_: AuthorizationRequired) { }
        assertEquals(0, calls.get())
        assertEquals("fake-old", store.saved?.refresh)
    }

    @Test fun refreshIsSerializedRotatesAndDisconnectClearsCredentials() = runBlocking {
        val store = FakeStore()
        val refreshes = AtomicInteger()
        val revocations = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("oauth2.googleapis.com", request.url.host)
            assertEquals("https", request.url.scheme)
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val form = buffer.readUtf8()
            val json = when (request.url.encodedPath) {
                "/token" -> {
                    refreshes.incrementAndGet()
                    assertTrue(form.contains("refresh_token=fake-old"))
                    """{"access_token":"fake-access","token_type":"Bearer","expires_in":3600,"refresh_token":"fake-rotated"}"""
                }
                "/revoke" -> { revocations.incrementAndGet(); assertTrue(form.contains("token=fake-rotated")); "{}" }
                else -> error("Unexpected endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = BrowserSessionManager(store, "fake-client", client, FakeKeys())
        assertEquals("brand", session.restore()?.id)
        val tokens = List(3) { async { session.token() } }.awaitAll()
        assertEquals(listOf("fake-access", "fake-access", "fake-access"), tokens)
        assertEquals(1, refreshes.get())
        assertEquals("fake-rotated", store.saved?.refresh)
        session.invalidate()
        assertNull(session.channel())
        try { session.token(); fail("Invalidated session provided a token") } catch (_: AuthorizationRequired) { }
        assertTrue(session.disconnect())
        assertNull(store.saved)
        assertEquals(1, revocations.get())
    }

    @Test fun rejectedRefreshRequiresReauthorizationWithoutWritingNewCredentials() = runBlocking {
        val store = FakeStore()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad request")
                .body("""{"error":"invalid_grant"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = BrowserSessionManager(store, "fake-client", client, FakeKeys())
        session.restore()
        try { session.token(); fail("Rejected refresh accepted") } catch (_: AuthorizationRequired) { }
        assertEquals("fake-old", store.saved?.refresh)
    }

    @Test fun disconnectDuringRefreshCannotRestoreTheSession() = runBlocking {
        val store = FakeStore()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = if (chain.request().url.encodedPath == "/token") {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                """{"access_token":"fake-access","token_type":"Bearer","expires_in":3600,"refresh_token":"fake-new"}"""
            } else "{}"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session = BrowserSessionManager(store, "fake-client", client, FakeKeys())
        session.restore()
        val result = async { try { session.token(); false } catch (_: AuthorizationRequired) { true } }
        assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
        session.invalidate()
        release.countDown()
        assertTrue(result.await())
        assertEquals("fake-old", store.saved?.refresh)
        assertTrue(session.disconnect())
        assertNull(store.saved)
    }
}
