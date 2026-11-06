package com.streamcontrols.app.ui

import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.streamcontrols.app.R
import com.streamcontrols.app.preferences.AppPreferences
import com.streamcontrols.app.preferences.Preferences
import com.streamcontrols.app.twitch.*
import com.streamcontrols.app.ui.theme.StreamControlsTheme
import com.streamcontrols.app.youtube.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

class TwitchConnectActivity : ComponentActivity() {
    private lateinit var api: TwitchApi
    private val preferences by lazy { AppPreferences(applicationContext) }
    private var operation: Job? = null
    private var verificationUri: String? = null
    private var browserConnection: CustomTabsServiceConnection? = null
    private var browserClient: CustomTabsClient? = null
    private var browserLaunchPending = false
    private var authorizationTabVisible = false
    private var browserTimeout: Job? = null
    private var userCode by mutableStateOf<String?>(null)
    private var channel by mutableStateOf<Channel?>(null)
    private var busy by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var deadlineAt by mutableLongStateOf(0L)
    private var status by mutableStateOf<String?>(null)
    private var message by mutableStateOf("Twitch will request permission to manage your channel's broadcast settings, including its title and category.")
    private var requestingNotificationPermission = false
    private var checkingNotificationPreference = false
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestingNotificationPermission = false
        if (verificationUri != null && channel == null) openBrowser()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TwitchSession.initialize(applicationContext)
        api = TwitchApi(getString(R.string.twitch_client_id))
        setContent {
            StreamControlsTheme {
                val clipboard = LocalClipboardManager.current
                val settings by preferences.data.collectAsStateWithLifecycle(initialValue = Preferences())
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Connect Twitch", style = MaterialTheme.typography.headlineSmall)
                    Text("Authorize Stream Controls to manage your Twitch stream.")
                    Text(message)
                    if (userCode != null && settings.accountConnectionNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this@TwitchConnectActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Text("Allow notifications to be alerted when Twitch connects. You can continue without them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    userCode?.let { code ->
                        var copied by remember(code) { mutableStateOf(false) }
                        var remaining by remember(deadlineAt) { mutableLongStateOf(((deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L) }
                        LaunchedEffect(deadlineAt) {
                            while (deadlineAt > 0L) {
                                remaining = ((deadlineAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1000L
                                if (remaining == 0L) break
                                delay(1000)
                            }
                        }
                        Text("Authorization code", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(code, modifier = Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge)
                            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(code)); copied = true },
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                                Text(if (copied) "Copied" else "Copy")
                            }
                        }
                        Text("Code expires in ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (verificationUri != null && channel == null) Button(onClick = ::openTwitch) { Text("Open Twitch") }
                    status?.let { Text("Status: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    channel?.let { ChannelCard(it, reconnect = false) }
                    if (channel != null) Button(onClick = { finish() }, enabled = !busy) { Text("Done") }
                    if (channel == null && !busy && verificationUri == null) Button(onClick = ::start) { Text("Connect Twitch") }
                    if (channel == null) TextButton(onClick = { operation?.cancel(); finish() }, enabled = !saving) { Text("Cancel") }
                }
                }
            }
        }
    }
    private fun start() {
        if (busy) return
        busy = true
        status = "Requesting code"
        operation = lifecycleScope.launch {
            try {
                val attempt = api.device()
                verificationUri = attempt.verificationUri
                deadlineAt = SystemClock.elapsedRealtime() + attempt.expiresSeconds * 1000
                userCode = attempt.userCode
                message = "Open Twitch and approve the connection. You can then close the authorization tab to return to Stream Controls. If asked, enter the code shown here."
                status = "Waiting for Twitch authorization..."
                val deadline = deadlineAt
                var interval = attempt.intervalSeconds
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(minOf(interval * 1000, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
                    if (SystemClock.elapsedRealtime() >= deadline) break
                    try {
                        val tokens = api.poll(attempt.deviceCode)
                        status = "Verifying channel"
                        val userId = api.validate(tokens.access)
                        val resolved = api.channel(tokens.access, userId)
                        status = "Saving connection"
                        saving = true
                        TwitchSession.connect(resolved, tokens)
                        saving = false
                        channel = resolved
                        verificationUri = null; userCode = null
                        status = "Connected"
                        message = "Twitch is connected. You can close the authorization tab."
                        setResult(Activity.RESULT_OK)
                        if (connectionNotificationsEnabled() && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            showTwitchConnectedNotification(this@TwitchConnectActivity)
                        }
                        returnFromAuthorizationTab()
                        return@launch
                    } catch (e: TwitchFailure) {
                        when (e.reason) {
                            "authorization_pending" -> Unit
                            "slow_down" -> interval = (interval + 5).coerceAtMost(60)
                            else -> throw e
                        }
                    }
                }
                message = "The Twitch sign-in code expired. Please try again."
                status = "Code expired"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "Connection failed"; message = "Could not connect to Twitch. Check your connection and try again." }
            finally { saving = false; busy = false; if (channel == null) { verificationUri = null; userCode = null; deadlineAt = 0L } }
        }
    }
    private fun openTwitch() {
        if (requestingNotificationPermission || checkingNotificationPreference || browserLaunchPending) return
        checkingNotificationPreference = true
        lifecycleScope.launch {
            try {
                val enabled = connectionNotificationsEnabled()
                if (verificationUri == null || channel != null) return@launch
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@TwitchConnectActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestingNotificationPermission = true
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else openBrowser()
            } finally { checkingNotificationPreference = false }
        }
    }
    private suspend fun connectionNotificationsEnabled(): Boolean = try {
        preferences.data.first().accountConnectionNotifications
    } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    private fun openBrowser() {
        if (verificationUri == null || browserLaunchPending) return
        browserClient?.let { launchCustomTab(it); return }
        browserConnection?.let { unbindService(it); browserConnection = null }
        val browser = customTabsBrowser()
        if (browser == null) { openBrowserFallback(); return }
        browserLaunchPending = true
        val connection = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                if (browserConnection !== this || !browserLaunchPending) return
                browserTimeout?.cancel()
                browserLaunchPending = false
                browserClient = client
                launchCustomTab(client)
            }
            override fun onServiceDisconnected(name: ComponentName) { browserClient = null }
        }
        browserConnection = connection
        try {
            if (!CustomTabsClient.bindCustomTabsService(this, browser, connection)) {
                browserConnection = null
                browserLaunchPending = false
                openBrowserFallback()
                return
            }
            browserTimeout = lifecycleScope.launch {
                delay(3000)
                if (browserLaunchPending && browserConnection === connection) {
                    browserLaunchPending = false
                    browserConnection = null
                    unbindService(connection)
                    openBrowserFallback()
                }
            }
        } catch (_: Exception) {
            browserConnection = null
            browserLaunchPending = false
            openBrowserFallback()
        }
    }
    private fun launchCustomTab(client: CustomTabsClient) {
        val uri = verificationUri ?: return
        try {
            val callback = object : CustomTabsCallback() {
                override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                    when (navigationEvent) {
                        CustomTabsCallback.TAB_SHOWN -> runOnUiThread { authorizationTabVisible = true }
                        CustomTabsCallback.TAB_HIDDEN -> runOnUiThread { authorizationTabVisible = false }
                    }
                }
            }
            val session = client.newSession(callback) ?: run { openBrowserFallback(); return }
            CustomTabsIntent.Builder(session)
                .setSendToExternalDefaultHandlerEnabled(false)
                .build().launchUrl(this, Uri.parse(uri))
        } catch (_: Exception) { openBrowserFallback() }
    }
    private fun openBrowserFallback() {
        val uri = verificationUri ?: return
        try {
            val browser = customTabsBrowser()
            val intent = if (browser != null) Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(browser)
                else Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(uri)
                }
            startActivity(intent)
        } catch (_: Exception) {
            message = "Could not open Twitch. Open twitch.tv/activate in a browser and enter the code."
        }
    }
    private fun customTabsBrowser(): String? =
        CustomTabsClient.getPackageName(this, listOf("com.android.chrome"), true)
            ?: CustomTabsClient.getPackageName(this, null)
    private fun returnFromAuthorizationTab() {
        // Only try while the Custom Tab reports that it is visible. Android may block
        // background activity launches, in which case the user can close the tab.
        if (!authorizationTabVisible) return
        try {
            startActivity(Intent(this, TwitchConnectActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            ))
        } catch (_: Exception) { /* The user can return manually. */ }
    }
    override fun onResume() {
        super.onResume()
        dismissTwitchConnectedNotification(this)
    }
    override fun onDestroy() {
        browserTimeout?.cancel()
        browserConnection?.let { unbindService(it) }
        browserConnection = null; browserClient = null
        operation?.cancel(); verificationUri = null
        super.onDestroy()
    }
}
