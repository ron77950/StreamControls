package com.streamcontrols.app.youtube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ThumbnailFetcherTest {
    @Test fun channelImagesAllowOnlySpecificHttpsHosts() {
        assertNotNull(channelImageUrl("https://yt3.googleusercontent.com/avatar"))
        assertNotNull(channelImageUrl("https://yt3.ggpht.com/avatar"))
        assertNull(channelImageUrl("http://yt3.googleusercontent.com/avatar"))
        assertNull(channelImageUrl("https://yt3.googleusercontent.com.evil.example/avatar"))
        assertNull(channelImageUrl("https://user:password@yt3.googleusercontent.com/avatar"))
        assertNull(channelImageUrl("https://yt3.googleusercontent.com:444/avatar"))
        assertNull(channelImageUrl("https://i.ytimg.com/avatar"))
    }

    @Test fun channelImageFetchHasNoCredentialsOrRetries() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found")
                .body("".toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val fetcher = ThumbnailFetcher(client, validateUrl = ::channelImageUrl) { String(it) }
        val url = "https://yt3.googleusercontent.com/avatar"
        assertNull(fetcher.load(url))
        assertNull(fetcher.load(url))
        assertEquals(1, calls.get())
        assertFalse(fetcher.client.followRedirects)
        assertFalse(fetcher.client.retryOnConnectionFailure)
    }

    @Test fun candidateSelectionUsesAvailableSizesAndUpgradesKnownHttpHost() {
        val json = JSONObject("""{"medium":{"url":"https://untrusted.example/image"},"high":{"url":"http://i.ytimg.com/vi/example/high.jpg"},"default":{"url":"https://i.ytimg.com/vi/example/default.jpg"}}""")
        assertEquals("https://i.ytimg.com/vi/example/high.jpg", thumbnailCandidate(json))
        assertNull(thumbnailCandidate(null))
        assertNull(thumbnailUrl("https://user:password@i.ytimg.com/image"))
        assertNull(thumbnailUrl("https://i.ytimg.com:444/image"))
        assertNull(thumbnailUrl("https://i.ytimg.com.untrusted.example/image"))
    }

    @Test fun unavailableImageIsNotRetriedAndHasNoCredentialsOrRedirects() {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            val request = chain.request()
            assertNull(request.header("Authorization"))
            assertNull(request.header("Cookie"))
            assertEquals("https", request.url.scheme)
            assertEquals("i.ytimg.com", request.url.host)
            calls.add(request.url.encodedPath)
            val code = if (request.url.encodedPath.endsWith("medium.jpg")) 404 else 200
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body("fake image".toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val fetcher = ThumbnailFetcher(client) { String(it) }
        assertFalse(fetcher.client.followRedirects)
        assertFalse(fetcher.client.followSslRedirects)
        assertFalse(fetcher.client.retryOnConnectionFailure)
        assertNull(fetcher.load("http://i.ytimg.com/vi/example/medium.jpg"))
        assertNull(fetcher.load("https://i.ytimg.com/vi/example/medium.jpg"))
        assertEquals(listOf("/vi/example/medium.jpg"), calls)
    }

    @Test fun untrustedDestinationsAreNeverRequested() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("Unexpected request") }.build()
        val fetcher = ThumbnailFetcher(client) { String(it) }
        assertNull(fetcher.load("https://untrusted.example/steal"))
        assertNull(fetcher.load("https://i.ytimg.com:444/image"))
        assertEquals(0, calls.get())
    }

    @Test fun redirectsAreNotFollowed() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().followRedirects(true).addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://untrusted.example/image")
                .body("".toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val result = ThumbnailFetcher(client) { String(it) }.load("https://i.ytimg.com/vi/example/default.jpg")
        assertNull(result)
        assertEquals(1, calls.get())
    }

    @Test fun successfulImageIsReused() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("image".toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val fetcher = ThumbnailFetcher(client) { String(it) }
        val url = "https://i.ytimg.com/vi/example/default.jpg"
        assertEquals("image", fetcher.load(url))
        assertEquals("image", fetcher.load(url))
        assertEquals(1, calls.get())
    }
}
