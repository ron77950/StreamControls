package com.streamcontrols.app.youtube

import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Only YouTube's image host is allowed; HTTP links are upgraded before any request. */
internal fun thumbnailUrl(value: String): HttpUrl? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.host != "i.ytimg.com" || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return when {
        url.isHttps && url.port == 443 -> url
        url.scheme == "http" && url.port == 80 -> url.newBuilder().scheme("https").port(443).build()
        else -> null
    }
}

internal fun thumbnailCandidate(thumbnails: JSONObject?): String? =
    listOf("medium", "high", "default", "standard").firstNotNullOfOrNull { size ->
        thumbnails?.optJSONObject(size)?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let(::thumbnailUrl)?.toString()
    }

/** Channel avatars use dedicated Google image hosts and must be HTTPS as returned by YouTube. */
internal fun channelImageUrl(value: String): HttpUrl? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.takeIf { it.host in setOf("yt3.googleusercontent.com", "yt3.ggpht.com") }
}

internal fun twitchImageUrl(value: String): HttpUrl? {
    val url = value.toHttpUrlOrNull() ?: return null
    if (!url.isHttps || url.host != "static-cdn.jtvnw.net" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url
}

internal fun channelImageCandidate(thumbnails: JSONObject?): String? =
    listOf("default", "medium", "high").firstNotNullOfOrNull { size ->
        thumbnails?.optJSONObject(size)?.optString("url")?.takeIf { it.isNotBlank() }
            ?.let(::channelImageUrl)?.toString()
    }

/** Separate, unauthenticated client: reject redirects and bound response size. */
internal class ThumbnailFetcher<T : Any>(client: OkHttpClient = OkHttpClient(), private val validateUrl: (String) -> HttpUrl? = ::thumbnailUrl, private val decode: (ByteArray) -> T?) {
    internal val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cookieJar(CookieJar.NO_COOKIES).build()
    private val attempted = mutableSetOf<String>()
    private val images = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = size > 16
    }

    // Remember attempts across navigation, including failures. Limit memory for long sessions.
    @Synchronized fun load(candidate: String?): T? {
        val url = candidate?.let(validateUrl) ?: return null
        val key = url.toString()
        images[key]?.let { return it }
        if (key in attempted || attempted.size >= 256) return null
        attempted.add(key)
        try {
            val image = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    AuthTrace.thumbnail("http_error", response.code)
                    return@use null
                }
                val body = response.body ?: return@use null
                if (body.contentLength() > MAX_BYTES) return@use null
                val source = body.source()
                source.request(MAX_BYTES + 1)
                if (source.buffer.size > MAX_BYTES) return@use null
                decode(source.readByteArray())
            }
            if (image != null) { images[key] = image; AuthTrace.thumbnail("loaded", null); return image }
            AuthTrace.thumbnail("unavailable", null)
        } catch (_: IOException) { AuthTrace.thumbnail("network_error", null) }
        catch (_: IllegalArgumentException) { AuthTrace.thumbnail("unavailable", null) }
        return null
    }
    private companion object { const val MAX_BYTES = 3_000_000L }
}
