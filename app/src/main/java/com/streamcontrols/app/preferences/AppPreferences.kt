package com.streamcontrols.app.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.dataStore by preferencesDataStore(name = "preferences")

enum class ThemePreference { SYSTEM, LIGHT, DARK }

data class Preferences(val theme: ThemePreference = ThemePreference.DARK, val presets: List<String> = emptyList(), val accountConnectionNotifications: Boolean = true)

class AppPreferences(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val presets = stringPreferencesKey("title_presets")
    private val accountNotifications = booleanPreferencesKey("account_connection_notifications")

    private val accountName = stringPreferencesKey("youtube_account")
    private val channelId = stringPreferencesKey("youtube_channel_id")

    val session = context.dataStore.data.map { values ->
        val account = values[accountName]
        val channel = values[channelId]
        if (account != null && channel != null) account to channel else null
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.remove(accountName); it.remove(channelId) }
    }

    val data = context.dataStore.data.map { values ->
        val savedTheme = ThemePreference.entries.find { it.name == values[theme] } ?: ThemePreference.DARK
        val titles = try {
            val array = JSONArray(values[presets] ?: "[]")
            List(array.length()) { array.getString(it) }
        } catch (_: org.json.JSONException) { emptyList() }
        Preferences(savedTheme, titles, values[accountNotifications] ?: true)
    }

    suspend fun setTheme(value: ThemePreference) {
        context.dataStore.edit { it[theme] = value.name }
    }

    suspend fun setPresets(value: List<String>) {
        context.dataStore.edit { it[presets] = JSONArray(value).toString() }
    }

    suspend fun setAccountConnectionNotifications(value: Boolean) {
        context.dataStore.edit { it[accountNotifications] = value }
    }
}
