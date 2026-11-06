package com.streamcontrols.app.ui

import android.net.Uri
import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.streamcontrols.app.R
import com.streamcontrols.app.ui.theme.StreamControlsTheme
import com.streamcontrols.app.youtube.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Browser authorization; credentials are never passed through activity results. */
class BrowserOAuthActivity : ComponentActivity() {
    private var pending: BrowserAttempt? = null
    private var token: String? = null
    private var refreshToken: String? = null
    private var dpopKeyAlias: String? = null
    private var dpopNonce: String? = null
    private var expiresIn: Long = 0
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("Connect your YouTube channel. Confirm the resolved channel before continuing.")
    private var channel by mutableStateOf<Channel?>(null)
    private var confirmDisconnect by mutableStateOf(false)

    private val launcher = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val attempt = pending
        pending = null // A callback, including cancellation, consumes the attempt.
        if (attempt == null || result.resultCode != AuthTabIntent.RESULT_OK || result.resultUri == null) {
            busy = false
            message = "Authorization was cancelled or could not be completed."
        } else {
            val code = attempt.validatedCode(result.resultUri!!)
            if (code == null) { busy = false; message = "Google returned an invalid authorization response." }
            else lifecycleScope.launch { complete(attempt, code) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamControlsTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Connect YouTube", style = MaterialTheme.typography.headlineSmall)
                    Text(message)
                    channel?.let { ChannelCard(it, reconnect = false) }
                    if (channel == null) Button(onClick = ::startAuthorization, enabled = !busy) { Text("Authorize in browser") }
                    if (channel != null) Button(onClick = ::useChannel, enabled = !busy) { Text("Use this channel") }
                    if (token != null) OutlinedButton(onClick = { confirmDisconnect = true }, enabled = !busy) { Text("Cancel and revoke") }
                    TextButton(onClick = { finish() }, enabled = !busy) { Text("Close") }
                }
                if (confirmDisconnect) AlertDialog(
                    onDismissRequest = { confirmDisconnect = false },
                    title = { Text("Revoke YouTube access?") },
                    text = { Text("This clears the local test session and asks Google to revoke access. Google may revoke grants for other clients in the same project and account.") },
                    confirmButton = { TextButton(onClick = { confirmDisconnect = false; disconnect() }) { Text("Disconnect") } },
                    dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } }
                )
            }
        }
    }

    private fun startAuthorization() {
        val id = getString(R.string.browser_oauth_client_id)
        if (id.isBlank()) { message = "YouTube sign-in is not configured for this build."; return }
        val provider = selectAuthTabBrowser(
            findChrome = { CustomTabsClient.getPackageName(this, listOf(CHROME_PACKAGE), true) },
            findDefault = { CustomTabsClient.getPackageName(this, null) },
            supportsAuthTab = { packageName ->
                @Suppress("DEPRECATION")
                val enabled = packageManager.getApplicationInfo(packageName, 0).enabled
                enabled && CustomTabsClient.isAuthTabSupported(this, packageName)
            }
        )
        if (provider == null) {
            message = "YouTube sign-in requires a browser that supports Auth Tab. Enable or update Chrome, or use another supported browser."
            return
        }
        val attempt = BrowserAttempt.create(id)
        pending = attempt
        busy = true
        try {
            val tab = AuthTabIntent.Builder().build()
            tab.intent.setPackage(provider)
            tab.launch(launcher, attempt.authorizationUri(), attempt.scheme)
        } catch (_: Exception) { pending = null; busy = false; message = "Could not open the browser authorization screen." }
    }

    private suspend fun complete(attempt: BrowserAttempt, code: String) {
        try {
            dpopKeyAlias?.let { AndroidDpopKeys.delete(it) }
            val alias = withContext(Dispatchers.IO) { AndroidDpopKeys.create() }
            dpopKeyAlias = alias
            val result = withContext(Dispatchers.IO) {
                val body = FormBody.Builder().add("client_id", attempt.clientId).add("code", code)
                    .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirect)
                    .add("grant_type", "authorization_code").build()
                DpopTokenClient(http).exchange(body, alias, code = code)
            }
            val response = result.json
            dpopNonce = result.nonce
            if (response.optString("token_type") != "Bearer" || !response.optString("scope").split(' ').contains(YOUTUBE_SCOPE)) throw IllegalStateException()
            val accessToken = response.optString("access_token").takeIf(String::isNotBlank) ?: throw IllegalStateException()
            refreshToken = response.optString("refresh_token").takeIf(String::isNotBlank) ?: throw MissingPersistentAuthorization()
            expiresIn = response.optLong("expires_in", 0).takeIf { it > 0 } ?: throw IllegalStateException()
            token = accessToken
            val resolved = YouTubeApi().channel(accessToken)
            channel = resolved
            message = "YouTube returned this channel. Confirm it is the intended channel before using it."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: NoYouTubeChannel) { message = "Google authorized the request, but YouTube returned no channels." }
        catch (_: MultipleYouTubeChannels) { message = "YouTube returned multiple channels; the app did not choose one." }
        catch (_: MissingPersistentAuthorization) { message = "Google did not provide permission for a persistent connection. Connect again and complete the consent screen." }
        catch (e: YouTubeFailure) { message = youtubeFailureMessage(e) }
        catch (_: Exception) { message = "The browser authorization or channel lookup failed. Check the Android client configuration and network." }
        finally { busy = false }
    }

    private fun useChannel() {
        val selected = channel ?: return
        val accessToken = token ?: return
        val refresh = refreshToken ?: return
        val alias = dpopKeyAlias ?: return
        busy = true
        lifecycleScope.launch {
            try {
                if (YouTubeApi().channel(accessToken).id != selected.id) throw IllegalStateException()
                BrowserSession.connect(accessToken, refresh, expiresIn, selected, alias, dpopNonce)
                dpopKeyAlias = null
                token = null; refreshToken = null
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { message = "Could not securely save or verify the YouTube connection. Please try again." }
            finally { busy = false }
        }
    }

    private fun disconnect() {
        val old = refreshToken ?: token ?: return
        token = null
        refreshToken = null
        channel = null
        busy = true
        lifecycleScope.launch {
            val revoked = try { withContext(Dispatchers.IO) {
                val body = FormBody.Builder().add("token", old).build()
                http.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke").post(body).build()).execute().use { it.isSuccessful }
            } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { false }
            message = if (revoked) "Browser test disconnected and Google confirmed revocation." else "Browser test disconnected locally, but Google did not confirm revocation."
            busy = false
        }
    }

    override fun onDestroy() {
        pending = null; token = null; refreshToken = null; dpopNonce = null
        dpopKeyAlias?.let { try { AndroidDpopKeys.delete(it) } catch (_: Exception) { } }
        dpopKeyAlias = null
        super.onDestroy()
    }
}

private class MissingPersistentAuthorization : Exception()

internal class BrowserAttempt private constructor(val clientId: String, val verifier: String, private val state: String) {
    val scheme = "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
    val redirect = "$scheme:/oauth2redirect"
    fun authorizationUri(): Uri {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
            .appendQueryParameter("client_id", clientId).appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("response_type", "code").appendQueryParameter("scope", YOUTUBE_SCOPE)
            .appendQueryParameter("access_type", "offline").appendQueryParameter("prompt", "consent select_account")
            .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state).build()
    }
    fun validatedCode(uri: Uri): String? {
        if (uri.scheme != scheme || uri.isOpaque || uri.authority != null || uri.path != "/oauth2redirect" || uri.fragment != null) return null
        if (uri.getQueryParameters("state").size != 1 || uri.getQueryParameters("code").size != 1 || uri.getQueryParameters("error").isNotEmpty()) return null
        val actual = uri.getQueryParameter("state") ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))) return null
        return uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
    }
    companion object {
        fun create(clientId: String): BrowserAttempt {
            val random = SecureRandom()
            fun value(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            return BrowserAttempt(clientId, value(), value())
        }
    }
}
