package com.streamcontrols.app.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.streamcontrols.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Owns browser credentials. Public state contains only the verified channel. */
interface AuthorizedSession {
    suspend fun restore(): Channel?
    fun channel(): Channel?
    suspend fun token(): String
    fun invalidate()
    suspend fun disconnect(): Boolean
}

interface ChannelSessions {
    suspend fun restoreAll(): List<Channel>
    fun hadRestoreFailures(): Boolean = false
    fun channel(id: String): Channel?
    suspend fun token(id: String): String
    fun invalidate(id: String)
    suspend fun disconnect(id: String): Boolean
    fun lastConnected(): Channel?
}

internal object BrowserSession : ChannelSessions {
    private lateinit var app: Context
    private lateinit var clientId: String
    private val managers = mutableMapOf<String, BrowserSessionManager>()
    private var latest: Channel? = null
    private var restoreFailures = false
    fun initialize(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            clientId = app.getString(R.string.browser_oauth_client_id)
        }
    }

    private fun key(id: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun manager(id: String): BrowserSessionManager = managers.getOrPut(id) {
        val hash = key(id)
        BrowserSessionManager(CredentialVault(app.noBackupFilesDir.resolve("youtube-channel-$hash.enc"), "streamcontrol.youtube.$hash", clientId), clientId)
    }
    override suspend fun restoreAll(): List<Channel> {
        restoreFailures = false
        val legacy = CredentialVault(app.noBackupFilesDir.resolve("youtube-session.enc"), "streamcontrol.browser.session.v1", clientId)
        val old = try { withContext(Dispatchers.IO) { legacy.read() } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { restoreFailures = true; null }
        if (old != null) {
            try {
                withContext(Dispatchers.IO) {
                    val hash = key(old.channel.id)
                    val next = CredentialVault(app.noBackupFilesDir.resolve("youtube-channel-$hash.enc"), "streamcontrol.youtube.$hash", clientId)
                    if (next.read() == null) next.persist(old)
                    legacy.clear()
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { restoreFailures = true }
        }
        val files = withContext(Dispatchers.IO) { app.noBackupFilesDir.listFiles()?.filter { it.name.matches(Regex("youtube-channel-[0-9a-f]{64}\\.enc")) }.orEmpty() }
        val channels = mutableListOf<Channel>()
        for (file in files) {
            val hash = file.name.removePrefix("youtube-channel-").removeSuffix(".enc")
            try {
                val vault = CredentialVault(file, "streamcontrol.youtube.$hash", clientId)
                val saved = withContext(Dispatchers.IO) { vault.read() } ?: continue
                if (key(saved.channel.id) != hash) throw AuthorizationRequired()
                val active = manager(saved.channel.id).restore() ?: throw AuthorizationRequired()
                channels.add(active)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { restoreFailures = true }
        }
        return channels
    }
    override fun hadRestoreFailures() = restoreFailures
    override fun channel(id: String) = managers[id]?.channel()
    override suspend fun token(id: String) = managers[id]?.token() ?: throw AuthorizationRequired()
    override fun invalidate(id: String) { managers[id]?.invalidate() }
    override suspend fun disconnect(id: String): Boolean = (managers.remove(id) ?: throw AuthorizationRequired()).disconnect()
    override fun lastConnected() = latest
    suspend fun connect(token: String, refresh: String, seconds: Long, channel: Channel, keyAlias: String, nonce: String?) {
        manager(channel.id).connect(token, refresh, seconds, channel, keyAlias, nonce)
        latest = channel
    }
}

internal interface CredentialStore {
    fun persist(saved: SavedCredential)
    fun read(): SavedCredential?
    fun clear()
}

internal data class SavedCredential(val refresh: String, val channel: Channel, val keyAlias: String?)

internal class BrowserSessionManager(
    private val vault: CredentialStore,
    private val clientId: String,
    client: OkHttpClient = OkHttpClient(),
    private val keys: DpopKeys = AndroidDpopKeys
) : AuthorizedSession {
    private val lock = Mutex()
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private val tokenClient = DpopTokenClient(http, keys)
    private var credential: String? = null
    private var pendingRevocation: String? = null
    private var access: String? = null
    private var expiresAt = 0L
    private var identity: Channel? = null
    private var keyAlias: String? = null
    private var nonce: String? = null
    @Volatile private var generation = 0L

    override suspend fun restore(): Channel? = lock.withLock {
        val previousGeneration = generation
        val saved = withContext(Dispatchers.IO) { vault.read() } ?: return@withLock null
        if (generation != previousGeneration) throw AuthorizationRequired()
        credential = saved.refresh
        identity = saved.channel
        keyAlias = saved.keyAlias
        nonce = null
        saved.channel
    }

    suspend fun connect(token: String, refresh: String, seconds: Long, channel: Channel, alias: String, initialNonce: String?) = lock.withLock {
        require(token.isNotBlank() && refresh.isNotBlank() && seconds > 0)
        if (!keys.exists(alias)) throw AuthorizationRequired()
        val previousGeneration = generation
        val oldAlias = keyAlias
        withContext(Dispatchers.IO) { vault.persist(SavedCredential(refresh, channel, alias)) }
        if (generation != previousGeneration) throw AuthorizationRequired()
        credential = refresh
        pendingRevocation = null
        access = token
        expiresAt = android.os.SystemClock.elapsedRealtime() + seconds.coerceAtMost(86400) * 1000
        identity = channel
        keyAlias = alias
        nonce = initialNonce
        if (oldAlias != null && oldAlias != alias) try { keys.delete(oldAlias) } catch (_: Exception) { }
    }

    override fun channel(): Channel? = identity

    override suspend fun token(): String = lock.withLock {
        val activeGeneration = generation
        access?.takeIf { android.os.SystemClock.elapsedRealtime() + 60_000 < expiresAt && generation == activeGeneration }?.let { return@withLock it }
        val original = credential ?: throw AuthorizationRequired()
        val activeIdentity = identity ?: throw AuthorizationRequired()
        val alias = keyAlias ?: throw AuthorizationRequired()
        if (!keys.exists(alias)) throw AuthorizationRequired()
        val currentGeneration = generation
        val response = withContext(Dispatchers.IO) {
            val body = FormBody.Builder().add("client_id", clientId).add("refresh_token", original).add("grant_type", "refresh_token").build()
            tokenClient.exchange(body, alias, previousNonce = nonce)
        }
        if (generation != currentGeneration) throw AuthorizationRequired()
        val json = response.json
        if (json.optString("token_type") != "Bearer") throw AuthorizationRequired()
        if (json.has("scope") && !json.optString("scope").split(' ').contains(YOUTUBE_SCOPE)) throw AuthorizationRequired()
        val newAccess = json.optString("access_token").takeIf { it.isNotBlank() } ?: throw AuthorizationRequired()
        val seconds = json.optLong("expires_in", 0).takeIf { it > 0 } ?: throw AuthorizationRequired()
        val rotated = json.optString("refresh_token").takeIf { it.isNotBlank() }
        if (rotated != null && rotated != original) {
            withContext(Dispatchers.IO) { vault.persist(SavedCredential(rotated, activeIdentity, alias)) }
            if (generation != currentGeneration) throw AuthorizationRequired()
            credential = rotated
        }
        access = newAccess
        nonce = response.nonce
        expiresAt = android.os.SystemClock.elapsedRealtime() + seconds.coerceAtMost(86400) * 1000
        newAccess
    }

    /** Invalidate synchronously before waiting for in-flight work to stop. */
    override fun invalidate() {
        generation++
        pendingRevocation = credential
        credential = null
        identity = null
        access = null
        expiresAt = 0
    }

    override suspend fun disconnect(): Boolean = lock.withLock {
        val saved = try { withContext(Dispatchers.IO) { vault.read() } } catch (_: Exception) { null }
        val previous = pendingRevocation ?: credential ?: saved?.refresh
        val oldAlias = keyAlias ?: saved?.keyAlias
        pendingRevocation = null
        credential = null; identity = null; access = null; expiresAt = 0; keyAlias = null; nonce = null
        withContext(Dispatchers.IO) {
            vault.clear()
            if (oldAlias != null) keys.delete(oldAlias)
        }
        if (previous == null) return@withLock false
        try {
            withContext(Dispatchers.IO) {
                val body = FormBody.Builder().add("token", previous).build()
                http.newCall(Request.Builder().url("https://oauth2.googleapis.com/revoke").post(body).build()).execute().use { it.isSuccessful }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { false }
    }
}

/** AES-GCM encrypted, atomic credential record bound to the configured OAuth client. */
internal class CredentialVault(private val path: java.io.File, private val alias: String, private val clientId: String) : CredentialStore {
    private val file get() = AtomicFile(path)
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) throw AuthorizationRequired()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun persist(saved: SavedCredential) {
        val payload = JSONObject().put("refresh", saved.refresh).put("channelId", saved.channel.id).put("channelName", saved.channel.name).put("clientId", clientId)
            .put("dpopKey", saved.keyAlias).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        check(cipher.iv.size == 12)
        val encrypted = cipher.doFinal(payload)
        val output = file.startWrite()
        try { output.write(cipher.iv); output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    override fun read(): SavedCredential? {
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        if (data.size < 29) throw AuthorizationRequired()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        if (json.getString("clientId") != clientId) throw AuthorizationRequired()
        return SavedCredential(json.getString("refresh"), Channel(json.getString("channelId"), json.getString("channelName")), json.optString("dpopKey").takeIf { it.isNotBlank() })
    }
    override fun clear() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
    }
}
