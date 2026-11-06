package com.streamcontrols.app.twitch

import com.streamcontrols.app.youtube.Channel
import com.streamcontrols.app.youtube.LiveStream
import com.streamcontrols.app.youtube.Platform
import com.streamcontrols.app.youtube.StreamVisibility
import com.streamcontrols.app.youtube.twitchImageUrl
import com.streamcontrols.app.youtube.isValidStreamTitle
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val TWITCH_SCOPE = "channel:manage:broadcast"
internal const val TWITCH_PREFIX = "twitch:"
internal class TwitchFailure(val status: Int, val reason: String = "") : Exception()
internal class TwitchReconnect : Exception()
internal class TwitchResponseInvalid : Exception()
internal data class DeviceAuthorization(val deviceCode: String, val userCode: String, val verificationUri: String, val intervalSeconds: Long, val expiresSeconds: Long)
internal data class TwitchTokens(val access: String, val refresh: String, val expiresSeconds: Long)

/** Fixed HTTPS origins and paths, no redirects, cookies, logging, or automatic retries. */
internal class TwitchApi(private val clientId: String, client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    private suspend fun request(host: String, path: String, token: String? = null, query: Map<String, String> = emptyMap(), method: String = "GET", body: RequestBody? = null): JSONObject {
        require((host == "id.twitch.tv" && path in setOf("/oauth2/device", "/oauth2/token", "/oauth2/validate", "/oauth2/revoke")) ||
            (host == "api.twitch.tv" && path in setOf("/helix/users", "/helix/streams", "/helix/channels")))
        val url = "https://$host$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url)
        if (host == "api.twitch.tv") builder.header("Client-Id", clientId)
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post(requireNotNull(body))
            "PATCH" -> builder.patch(requireNotNull(body))
            "GET" -> Unit
            else -> error("Unsupported request")
        }
        val call = http.newCall(builder.build())
        val response = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { if (continuation.isActive) continuation.resume(response) else response.close() }
            })
        }
        response.use {
            val content = it.body?.string().orEmpty()
            val json = if (content.isBlank()) JSONObject() else try { JSONObject(content) } catch (_: Exception) { throw TwitchResponseInvalid() }
            if (!it.isSuccessful) {
                // Only fixed protocol markers are retained; never expose server response text.
                val protocolReasons = setOf("authorization_pending", "slow_down", "expired_token", "access_denied")
                val reason = listOf(json.optString("message"), json.optString("error")).firstOrNull { it in protocolReasons }.orEmpty()
                throw TwitchFailure(it.code, reason)
            }
            return json
        }
    }

    suspend fun device(): DeviceAuthorization {
        val form = FormBody.Builder().add("client_id", clientId).add("scopes", TWITCH_SCOPE).build()
        val json = request("id.twitch.tv", "/oauth2/device", method = "POST", body = form)
        val deviceCode = json.getString("device_code").takeIf { it.isNotBlank() } ?: throw TwitchResponseInvalid()
        val userCode = json.getString("user_code").takeIf { it.isNotBlank() } ?: throw TwitchResponseInvalid()
        val uri = json.optString("verification_uri").toHttpUrlOrNullSafe() ?: throw TwitchResponseInvalid()
        if (!uri.isHttps || uri.host != "www.twitch.tv" || uri.port != 443 || uri.encodedPath != "/activate" || uri.username.isNotEmpty() || uri.password.isNotEmpty()) throw TwitchResponseInvalid()
        // Twitch may include the user-facing code in the activation URL. Forward it only
        // when it exactly matches the displayed code and is not the separate device secret.
        val safeUri = "https://www.twitch.tv/activate".toHttpUrl().newBuilder().apply {
            if (uri.queryParameter("public") == "true") addQueryParameter("public", "true")
            if (userCode != deviceCode && uri.queryParameterValues("device-code") == listOf(userCode)) addQueryParameter("device-code", userCode)
        }.build()
        return DeviceAuthorization(deviceCode, userCode, safeUri.toString(),
            json.getLong("interval").takeIf { it in 1..60 } ?: throw TwitchResponseInvalid(),
            json.getLong("expires_in").takeIf { it in 1..3600 } ?: throw TwitchResponseInvalid())
    }

    suspend fun poll(deviceCode: String): TwitchTokens {
        val form = FormBody.Builder().add("client_id", clientId).add("scopes", TWITCH_SCOPE).add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build()
        return tokens(request("id.twitch.tv", "/oauth2/token", method = "POST", body = form))
    }
    suspend fun refresh(refresh: String): TwitchTokens {
        val form = FormBody.Builder().add("client_id", clientId).add("refresh_token", refresh).add("grant_type", "refresh_token").build()
        return tokens(request("id.twitch.tv", "/oauth2/token", method = "POST", body = form))
    }
    private fun tokens(json: JSONObject): TwitchTokens {
        if (!json.optString("token_type").equals("bearer", ignoreCase = true)) throw TwitchResponseInvalid()
        val scopes = json.optJSONArray("scope") ?: throw TwitchResponseInvalid()
        if ((0 until scopes.length()).none { scopes.optString(it) == TWITCH_SCOPE }) throw TwitchResponseInvalid()
        return TwitchTokens(json.getString("access_token").takeIf { it.isNotBlank() } ?: throw TwitchResponseInvalid(),
            json.getString("refresh_token").takeIf { it.isNotBlank() } ?: throw TwitchResponseInvalid(),
            json.getLong("expires_in").takeIf { it > 0 } ?: throw TwitchResponseInvalid())
    }
    suspend fun validate(token: String, expectedId: String? = null): String {
        val json = request("id.twitch.tv", "/oauth2/validate", token)
        if (json.getString("client_id") != clientId || json.optLong("expires_in", 0) <= 0) throw TwitchReconnect()
        val scopes = json.optJSONArray("scopes") ?: throw TwitchReconnect()
        if ((0 until scopes.length()).none { scopes.optString(it) == TWITCH_SCOPE }) throw TwitchReconnect()
        val id = json.optString("user_id").takeIf { it.matches(Regex("[0-9]+")) } ?: throw TwitchReconnect()
        if (expectedId != null && id != expectedId) throw TwitchReconnect()
        return id
    }
    suspend fun channel(token: String, id: String): Channel {
        val array = request("api.twitch.tv", "/helix/users", token, mapOf("id" to id)).getJSONArray("data")
        if (array.length() != 1) throw TwitchResponseInvalid()
        val user = array.getJSONObject(0)
        if (user.getString("id") != id) throw TwitchReconnect()
        val image = user.optString("profile_image_url").let(::twitchImageUrl)?.toString()
        return Channel(TWITCH_PREFIX + id, user.getString("display_name").takeIf { it.isNotBlank() } ?: throw TwitchResponseInvalid(), image, Platform.TWITCH)
    }
    suspend fun streams(token: String, id: String): List<LiveStream> {
        val array = request("api.twitch.tv", "/helix/streams", token, mapOf("user_id" to id)).getJSONArray("data")
        if (array.length() > 1) throw TwitchResponseInvalid()
        if (array.length() == 0) return emptyList()
        val item = array.getJSONObject(0)
        if (item.getString("user_id") != id || item.getString("type") != "live") throw TwitchResponseInvalid()
        val streamId = item.getString("id").takeIf { it.matches(Regex("[0-9]+")) } ?: throw TwitchResponseInvalid()
        val thumbnail = item.optString("thumbnail_url").replace("{width}", "320").replace("{height}", "180").let(::twitchImageUrl)?.toString()
        return listOf(LiveStream(TWITCH_PREFIX + streamId, item.getString("title"), thumbnail,
            item.optString("started_at").takeIf { it.isNotBlank() }, StreamVisibility.UNKNOWN, TWITCH_PREFIX + id, Platform.TWITCH))
    }
    suspend fun title(token: String, id: String): String {
        val array = request("api.twitch.tv", "/helix/channels", token, mapOf("broadcaster_id" to id)).getJSONArray("data")
        if (array.length() != 1) throw TwitchResponseInvalid()
        val item = array.getJSONObject(0)
        if (item.getString("broadcaster_id") != id) throw TwitchReconnect()
        return item.getString("title")
    }
    suspend fun updateTitle(token: String, id: String, title: String) {
        require(isValidStreamTitle(title, Platform.TWITCH)) { "Invalid Twitch title" }
        val body = JSONObject().put("title", title).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        request("api.twitch.tv", "/helix/channels", token, mapOf("broadcaster_id" to id), "PATCH", body)
    }
    suspend fun revoke(token: String): Boolean {
        val form = FormBody.Builder().add("client_id", clientId).add("token", token).build()
        request("id.twitch.tv", "/oauth2/revoke", method = "POST", body = form)
        return true
    }
}

private fun String.toHttpUrlOrNullSafe() = try { toHttpUrl() } catch (_: IllegalArgumentException) { null }
