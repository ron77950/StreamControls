package com.streamcontrols.app

import android.os.Bundle
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.streamcontrols.app.youtube.YouTubeApi
import com.streamcontrols.app.youtube.BrowserSession
import com.streamcontrols.app.ui.BrowserOAuthActivity
import com.streamcontrols.app.ui.TwitchConnectActivity
import com.streamcontrols.app.ui.dismissTwitchConnectedNotification
import com.streamcontrols.app.twitch.TwitchSession
import android.content.Intent
import com.streamcontrols.app.youtube.StreamsViewModel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamcontrols.app.preferences.AppPreferences
import com.streamcontrols.app.preferences.Preferences
import com.streamcontrols.app.preferences.ThemePreference
import com.streamcontrols.app.ui.StreamControlApp
import com.streamcontrols.app.ui.theme.StreamControlsTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BrowserSession.initialize(applicationContext)
        TwitchSession.initialize(applicationContext)
        val preferences = AppPreferences(applicationContext)
        val model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(preferences) as T
        })[SettingsViewModel::class.java]
        val streams = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return StreamsViewModel(YouTubeApi(), preferences) as T
            }
        })[StreamsViewModel::class.java]
        lifecycleScope.launch {
            TwitchSession.connections.collect { channel -> streams.connectTwitch(channel) }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    while (streams.state.value.busy) delay(1000)
                    val validated = streams.validateTwitch()
                    delay(if (validated) 50 * 60_000L else 60_000L)
                }
            }
        }
        setContent {
            val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) streams.connectBrowser()
            }
            val twitchAuthorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
            val settings = model.preferences.collectAsStateWithLifecycle().value
            val dark = when (settings.theme) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.DARK -> true
                ThemePreference.LIGHT -> false
            }
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            StreamControlsTheme(darkTheme = dark) {
                StreamControlApp(settings, model::setTheme, model::setPresets, { enabled ->
                    model.setAccountConnectionNotifications(enabled)
                    if (!enabled) dismissTwitchConnectedNotification(this)
                }, streams,
                    chooseAccount = { authorization.launch(Intent(this, BrowserOAuthActivity::class.java)) },
                    chooseTwitch = { twitchAuthorization.launch(Intent(this, TwitchConnectActivity::class.java)) })
            }
        }
    }
}

class SettingsViewModel(private val store: AppPreferences) : ViewModel() {
    val preferences = store.data.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Preferences())
    fun setTheme(theme: ThemePreference) { viewModelScope.launch { store.setTheme(theme) } }
    fun setPresets(presets: List<String>) { viewModelScope.launch { store.setPresets(presets) } }
    fun setAccountConnectionNotifications(enabled: Boolean) { viewModelScope.launch { store.setAccountConnectionNotifications(enabled) } }
}
