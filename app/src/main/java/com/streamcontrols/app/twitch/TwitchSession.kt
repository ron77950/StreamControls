package com.streamcontrols.app.twitch

import android.content.Context
import android.os.SystemClock
import com.streamcontrols.app.R
import com.streamcontrols.app.youtube.Channel
import com.streamcontrols.app.youtube.CredentialStore
import com.streamcontrols.app.youtube.CredentialVault
import com.streamcontrols.app.youtube.Platform
import com.streamcontrols.app.youtube.SavedCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.MessageDigest

interface TwitchSessions {
    suspend fun restoreAll(): List<Channel>
    fun hadRestoreFailures(): Boolean
    fun lastConnected(): Channel?
    suspend fun token(channelId: String): String
    fun invalidate(channelId: String)
    suspend fun disconnect(channelId: String): Boolean
}

internal object TwitchSession : TwitchSessions {
    private lateinit var app: Context
    private lateinit var clientId: String
    private lateinit var service: TwitchApi
    private val sessions = mutableMapOf<String, TwitchSessionManager>()
    private var latest: Channel? = null
    private val connectionEvents = MutableSharedFlow<Channel>(extraBufferCapacity = 1)
    val connections = connectionEvents.asSharedFlow()
    private var failed = false
    internal fun api() = service
    fun initialize(context: Context) {
        if (!::app.isInitialized) { app = context.applicationContext; clientId = app.getString(R.string.twitch_client_id); service = TwitchApi(clientId) }
    }
    private fun hash(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun vault(id: String): CredentialStore {
        val key = hash(id)
        return CredentialVault(app.noBackupFilesDir.resolve("twitch-channel-$key.enc"), "streamcontrol.twitch.$key", clientId)
    }
    private fun manager(id: String) = sessions.getOrPut(id) { TwitchSessionManager(vault(id), service) }
    override suspend fun restoreAll(): List<Channel> {
        failed = false
        val files = withContext(Dispatchers.IO) { app.noBackupFilesDir.listFiles()?.filter { it.name.matches(Regex("twitch-channel-[0-9a-f]{64}\\.enc")) }.orEmpty() }
        val channels = mutableListOf<Channel>()
        for (file in files) {
            try {
                val key = file.name.removePrefix("twitch-channel-").removeSuffix(".enc")
                val saved = withContext(Dispatchers.IO) { CredentialVault(file, "streamcontrol.twitch.$key", clientId).read() } ?: continue
                if (!saved.channel.id.startsWith(TWITCH_PREFIX) || hash(saved.channel.id) != key) throw TwitchReconnect()
                val channel = manager(saved.channel.id).restore() ?: throw TwitchReconnect()
                channels.add(channel)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
        }
        return channels
    }
    override fun hadRestoreFailures() = failed
    override fun lastConnected() = latest
    override suspend fun token(channelId: String) = sessions[channelId]?.token() ?: throw TwitchReconnect()
    override fun invalidate(channelId: String) { sessions[channelId]?.invalidate() }
    override suspend fun disconnect(channelId: String) = (sessions.remove(channelId) ?: throw TwitchReconnect()).disconnect()
    suspend fun connect(channel: Channel, tokens: TwitchTokens) {
        manager(channel.id).connect(channel, tokens)
        latest = channel
        connectionEvents.emit(channel)
    }
}

internal class TwitchSessionManager(private val vault: CredentialStore, private val api: TwitchApi) {
    private val lock = Mutex()
    private var channel: Channel? = null
    private var refresh: String? = null
    private var access: String? = null
    private var expiresAt = 0L
    private var validatedAt = 0L
    @Volatile private var generation = 0L
    @Volatile private var pendingRevocation: String? = null

    suspend fun restore(): Channel? = lock.withLock {
        val saved = withContext(Dispatchers.IO) { vault.read() } ?: return@withLock null
        channel = saved.channel.copy(platform = Platform.TWITCH)
        refresh = saved.refresh
        channel
    }
    suspend fun connect(channel: Channel, tokens: TwitchTokens) = lock.withLock {
        val before = generation
        withContext(Dispatchers.IO) { vault.persist(SavedCredential(tokens.refresh, channel, null)) }
        if (generation != before) throw TwitchReconnect()
        this.channel = channel
        refresh = tokens.refresh
        access = tokens.access
        expiresAt = SystemClock.elapsedRealtime() + tokens.expiresSeconds.coerceAtMost(86400) * 1000
        validatedAt = SystemClock.elapsedRealtime()
    }
    suspend fun token(): String = lock.withLock {
        val expected = channel ?: throw TwitchReconnect()
        val id = expected.id.removePrefix(TWITCH_PREFIX).takeIf { it.matches(Regex("[0-9]+")) } ?: throw TwitchReconnect()
        val before = generation
        var current = access?.takeIf { SystemClock.elapsedRealtime() + 60_000 < expiresAt }
        if (current != null && (validatedAt == 0L || SystemClock.elapsedRealtime() - validatedAt >= 50 * 60_000)) {
            try { api.validate(current, id); validatedAt = SystemClock.elapsedRealtime() }
            catch (e: TwitchFailure) { if (e.status == 401) { current = null; access = null } else throw e }
        }
        if (current != null) {
            if (generation != before) throw TwitchReconnect()
            return@withLock current
        }
        val previous = refresh?.takeIf { it.isNotBlank() } ?: throw TwitchReconnect()
        // Public-client refresh tokens are single use. Record an uncertain state before sending,
        // so a crash or timeout cannot cause the old token to be retried after restart.
        val tokens = withContext(NonCancellable + Dispatchers.IO) {
            vault.persist(SavedCredential("", expected, null))
            refresh = null
            if (generation != before) throw TwitchReconnect()
            try {
                val next = api.refresh(previous)
                if (generation != before) { pendingRevocation = next.access; throw TwitchReconnect() }
                vault.persist(SavedCredential(next.refresh, expected, null))
                if (generation != before) { pendingRevocation = next.access; throw TwitchReconnect() }
                refresh = next.refresh
                next
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { throw TwitchReconnect() }
        }
        if (generation != before) { pendingRevocation = tokens.access; throw TwitchReconnect() }
        access = tokens.access
        expiresAt = SystemClock.elapsedRealtime() + tokens.expiresSeconds.coerceAtMost(86400) * 1000
        validatedAt = 0L
        val resolved = api.validate(tokens.access, id)
        if (resolved != id || generation != before) {
            if (generation != before) pendingRevocation = tokens.access
            throw TwitchReconnect()
        }
        validatedAt = SystemClock.elapsedRealtime()
        tokens.access
    }
    fun invalidate() {
        generation++
        pendingRevocation = access
        access = null; refresh = null; channel = null
    }
    suspend fun disconnect(): Boolean = lock.withLock {
        val token = pendingRevocation
        pendingRevocation = null
        withContext(Dispatchers.IO) { vault.clear() }
        if (token == null) return@withLock false
        try { api.revoke(token) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
    }
}
