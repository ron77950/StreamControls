package com.streamcontrols.app.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamcontrols.app.preferences.AppPreferences
import com.streamcontrols.app.twitch.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

private const val TITLE_UPDATED_MESSAGE = "Title updated."
private const val STREAM_ENDED_MESSAGE = "Livestream ended."

enum class MessageScreen { ALL, STREAMS, ACCOUNTS, MANAGE }

data class StreamsState(
    val channels: List<Channel> = emptyList(),
    val reconnectIds: Set<String> = emptySet(),
    val streams: List<LiveStream> = emptyList(),
    val selectedId: String? = null,
    val draft: String = "",
    val editingBaseTitle: String? = null,
    val busy: Boolean = false,
    val reconnect: Boolean = false,
    val message: String? = null,
    val messageScreen: MessageScreen = MessageScreen.ALL,
    val conflictTitle: String? = null,
    val loaded: Boolean = false
) {
    fun messageFor(screen: MessageScreen): String? = message.takeIf { messageScreen == MessageScreen.ALL || messageScreen == screen }
}

class StreamsViewModel(private val api: YouTubeApi, private val preferences: AppPreferences, private val session: ChannelSessions = BrowserSession, private val twitchSession: TwitchSessions = TwitchSession) : ViewModel() {
    private val mutable = MutableStateFlow(StreamsState(busy = true))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var disconnecting = false
    private var restoreWarning: String? = null

    init {
        work = viewModelScope.launch {
            try {
                val legacy = preferences.session.first()
                preferences.clearSession()
                var restoreFailed = false
                suspend fun restore(block: suspend () -> List<Channel>): List<Channel> = try { block() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { restoreFailed = true; emptyList() }
                val saved = restore { session.restoreAll() } + restore { twitchSession.restoreAll() }
                restoreWarning = if (restoreFailed || session.hadRestoreFailures() || twitchSession.hadRestoreFailures()) "Some saved connections could not be restored. Reconnect those channels." else null
                mutable.value = StreamsState(channels = saved, reconnect = legacy != null && saved.isEmpty(),
                    message = restoreWarning ?: if (legacy != null && saved.isEmpty()) "Reconnect YouTube to continue." else null)
                if (saved.isNotEmpty()) refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = StreamsState(reconnect = true, message = messageFor(e)) }
        }
    }

    fun connectBrowser() {
        val proposed = session.lastConnected() ?: return
        val previousWork = work
        work = viewModelScope.launch {
            previousWork?.join()
            mutable.update { it.copy(busy = true, message = null, messageScreen = MessageScreen.ACCOUNTS) }
            try {
                val resolved = api.channel(session.token(proposed.id))
                if (resolved.id != proposed.id) throw WrongChannel()
                mutable.update { old -> old.copy(channels = old.channels.filterNot { it.id == resolved.id } + resolved,
                    reconnectIds = old.reconnectIds - resolved.id, reconnect = false, busy = false) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { old -> old.copy(channels = old.channels.filterNot { it.id == proposed.id } + proposed,
                    reconnectIds = old.reconnectIds + proposed.id, busy = false, message = messageFor(e), messageScreen = MessageScreen.ACCOUNTS) }
            }
        }
    }

    fun connectTwitch(connected: Channel? = null) {
        val proposed = connected ?: twitchSession.lastConnected() ?: return
        val previous = work
        work = viewModelScope.launch {
            previous?.join()
            mutable.update { it.copy(busy = true, message = null, messageScreen = MessageScreen.ACCOUNTS) }
            try {
                val token = twitchSession.token(proposed.id)
                val resolved = TwitchSession.api().channel(token, proposed.id.removePrefix(TWITCH_PREFIX))
                if (resolved.id != proposed.id) throw TwitchReconnect()
                mutable.update { it.copy(channels = it.channels.filterNot { channel -> channel.id == resolved.id } + resolved,
                    reconnectIds = it.reconnectIds - resolved.id, busy = false) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(channels = it.channels.filterNot { channel -> channel.id == proposed.id } + proposed,
                    reconnectIds = it.reconnectIds + proposed.id, busy = false, message = messageFor(e), messageScreen = MessageScreen.ACCOUNTS) }
            }
        }
    }

    // Twitch requires validation at startup and hourly while an app maintains an active session.
    suspend fun validateTwitch(): Boolean {
        if (mutable.value.busy) return false
        var success = true
        for (channel in mutable.value.channels.filter { it.platform == Platform.TWITCH }) {
            try { twitchSession.token(channel.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is TwitchReconnect || (e is TwitchFailure && e.status == 401)) {
                    mutable.update { old ->
                        if (old.channels.none { it.id == channel.id }) old else {
                            val selectedRemoved = old.streams.any { it.id == old.selectedId && it.channelId == channel.id }
                            old.copy(reconnectIds = old.reconnectIds + channel.id,
                                streams = old.streams.filterNot { it.channelId == channel.id },
                                selectedId = if (selectedRemoved) null else old.selectedId,
                                draft = if (selectedRemoved) "" else old.draft,
                                editingBaseTitle = if (selectedRemoved) null else old.editingBaseTitle,
                                conflictTitle = if (selectedRemoved) null else old.conflictTitle,
                                message = "${channel.name}: Reconnect Twitch.", messageScreen = MessageScreen.ALL)
                        }
                    }
                } else {
                    success = false
                }
            }
        }
        return success
    }

    fun refresh() {
        if (mutable.value.busy || mutable.value.channels.isEmpty()) return
        work = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null, messageScreen = MessageScreen.STREAMS) }
            val streams = mutableListOf<LiveStream>()
            val resolvedChannels = mutableListOf<Channel>()
            val reconnect = mutable.value.reconnectIds.toMutableSet()
            val errors = mutableListOf<String>()
            for (channel in mutable.value.channels) {
                try {
                    val token = if (channel.platform == Platform.TWITCH) twitchSession.token(channel.id) else session.token(channel.id)
                    val resolved = if (channel.platform == Platform.TWITCH)
                        TwitchSession.api().channel(token, channel.id.removePrefix(TWITCH_PREFIX)) else api.channel(token)
                    if (resolved.id != channel.id) throw WrongChannel()
                    resolvedChannels.add(resolved)
                    reconnect.remove(channel.id)
                    streams.addAll(if (channel.platform == Platform.TWITCH) TwitchSession.api().streams(token, channel.id.removePrefix(TWITCH_PREFIX)) else api.streams(token, channel.id))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (resolvedChannels.none { it.id == channel.id }) resolvedChannels.add(channel)
                    if (e is AuthorizationRequired || e is TwitchReconnect || e is WrongChannel || (e is YouTubeFailure && e.status == 401) || (e is TwitchFailure && e.status == 401)) reconnect.add(channel.id)
                    errors.add("${channel.name}: ${messageFor(e)}")
                }
            }
            mutable.update { old -> reconcileStreams(old.copy(channels = resolvedChannels, reconnectIds = reconnect), streams)
                .copy(message = (listOfNotNull(restoreWarning) + errors).takeIf { it.isNotEmpty() }?.joinToString("\n"), messageScreen = MessageScreen.STREAMS) }
        }
    }

    fun select(stream: LiveStream) {
        if (mutable.value.busy) return
        mutable.update { it.copy(selectedId = stream.id, draft = stream.title, editingBaseTitle = stream.title, conflictTitle = null, message = null) }
    }
    fun edit(value: String) { mutable.update { it.copy(draft = value, conflictTitle = null) } }

    fun updateTitle(acceptConflict: Boolean = false) {
        val before = mutable.value
        val selected = before.streams.find { it.id == before.selectedId } ?: return
        if (before.busy || !isValidStreamTitle(before.draft, selected.platform) || before.draft == selected.title) return
        if (selected.platform == Platform.TWITCH) { updateTwitchTitle(before, selected, acceptConflict); return }
        val desired = before.draft
        mutable.update { it.copy(busy = true, message = "Updating title on YouTube…", messageScreen = MessageScreen.MANAGE, conflictTitle = null) }
        work = viewModelScope.launch {
            try {
                val channelId = selected.channelId.takeIf { it.isNotBlank() } ?: throw AuthorizationRequired()
                val token = tokenForChannel(channelId)
                if (api.streams(token, channelId).none { it.id == selected.id }) throw StreamEnded()
                val video = api.video(token, selected.id)
                if (video.id != selected.id || video.channelId != channelId) throw WrongChannel()
                // A confirmation is valid only for the exact title shown to the user.
                val acknowledged = if (acceptConflict) before.conflictTitle else null
                if (video.title != (before.editingBaseTitle ?: selected.title) && video.title != acknowledged) {
                    mutable.update { it.copy(busy = false, conflictTitle = video.title, message = null) }
                    return@launch
                }
                try { api.update(token, video, desired) }
                catch (e: IOException) { /* The server may already have applied the update. */ }
                catch (e: IncompleteResponse) { /* A malformed response does not establish whether the write occurred. */ }
                catch (e: YouTubeFailure) { if (e.status !in 500..599) throw e }
                mutable.update { it.copy(message = "Checking the updated title on YouTube…") }
                when (val confirmation = confirmTitle(selected.id, channelId, desired, read = { api.videoTitle(token, selected.id) })) {
                    TitleConfirmation.Confirmed -> mutable.update { old -> old.copy(busy = false, streams = old.streams.map { if (it.id == selected.id) it.copy(title = desired) else it }, draft = desired, editingBaseTitle = desired, message = TITLE_UPDATED_MESSAGE) }
                    is TitleConfirmation.Different -> mutable.update { old -> old.copy(busy = false, streams = old.streams.map { if (it.id == selected.id) it.copy(title = confirmation.title) else it }, message = "YouTube is still showing a different title, so the update wasn't confirmed. Refresh on the Streams page before trying again.") }
                    TitleConfirmation.Unavailable -> throw UncertainUpdate()
                    TitleConfirmation.WrongIdentity -> throw WrongChannel()
                    TitleConfirmation.AuthorizationExpired -> {
                        mutable.update { it.copy(reconnectIds = it.reconnectIds + channelId, streams = it.streams.filterNot { stream -> stream.channelId == channelId }, selectedId = null, draft = "", editingBaseTitle = null, busy = false, message = "The update may have gone through, but authorization expired before it could be confirmed. Reconnect and check the title.") }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(e, selected.channelId) }
        }
    }

    private fun updateTwitchTitle(before: StreamsState, selected: LiveStream, acceptConflict: Boolean) {
        val channelId = selected.channelId
        val id = channelId.removePrefix(TWITCH_PREFIX)
        val desired = before.draft
        if (!id.matches(Regex("[0-9]+")) || before.busy || channelId in before.reconnectIds) return
        mutable.update { it.copy(busy = true, message = "Updating title on Twitch…", messageScreen = MessageScreen.MANAGE, conflictTitle = null) }
        work = viewModelScope.launch {
            var writeAttempted = false
            try {
                val token = twitchSession.token(channelId)
                val twitch = TwitchSession.api()
                if (twitch.streams(token, id).none { it.id == selected.id }) throw StreamEnded()
                val current = twitch.title(token, id)
                val acknowledged = if (acceptConflict) before.conflictTitle else null
                if (current != (before.editingBaseTitle ?: selected.title) && current != acknowledged) {
                    mutable.update { it.copy(busy = false, conflictTitle = current, message = null) }
                    return@launch
                }
                writeAttempted = true
                try { twitch.updateTitle(token, id, desired) }
                catch (_: IOException) { /* Read back the uncertain write, never retry it. */ }
                catch (_: TwitchResponseInvalid) { /* A malformed response may follow an accepted write. */ }
                catch (e: TwitchFailure) { if (e.status !in 500..599) throw e }
                mutable.update { it.copy(message = "Checking the updated title on Twitch…") }
                var latest: String? = null
                var lastReadOk = false
                for (attempt in 1..3) {
                    delay(750)
                    try {
                        latest = twitch.title(token, id)
                        lastReadOk = true
                        if (latest == desired) break
                    } catch (e: CancellationException) { throw e }
                    catch (e: TwitchFailure) { if (e.status == 401) throw e; lastReadOk = false }
                    catch (_: IOException) { lastReadOk = false }
                    catch (_: TwitchResponseInvalid) { lastReadOk = false }
                    catch (_: org.json.JSONException) { lastReadOk = false }
                }
                when {
                    lastReadOk && latest == desired -> mutable.update { old -> old.copy(busy = false,
                        streams = old.streams.map { if (it.id == selected.id) it.copy(title = desired) else it },
                        draft = desired, editingBaseTitle = desired, message = TITLE_UPDATED_MESSAGE) }
                    lastReadOk && latest != null -> mutable.update { old -> old.copy(busy = false,
                        streams = old.streams.map { if (it.id == selected.id) it.copy(title = latest) else it },
                        message = "Twitch is still showing a different title. Refresh the Streams page to check.") }
                    else -> mutable.update { it.copy(busy = false, message = "Couldn't confirm the title with Twitch. Refresh the Streams page to check before trying again.") }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                handleFailure(e, channelId)
                if (writeAttempted && (e is TwitchReconnect || (e is TwitchFailure && e.status == 401))) {
                    mutable.update { it.copy(message = "The title may have changed, but Twitch authorization expired before it could be confirmed. Reconnect and check the title.") }
                }
            }
        }
    }

    fun dismissConflict() { mutable.update { it.copy(conflictTitle = null) } }

    fun clearCompletedMessage() {
        mutable.update { if (it.message == TITLE_UPDATED_MESSAGE || it.message == STREAM_ENDED_MESSAGE) it.copy(message = null) else it }
    }

    fun endSelectedStream(confirmedId: String) {
        val before = mutable.value
        val selected = before.streams.find { it.id == before.selectedId } ?: return
        if (selected.id != confirmedId || selected.platform != Platform.YOUTUBE) return
        val channelId = selected.channelId.takeIf { it.isNotBlank() } ?: return
        if (before.busy || channelId in before.reconnectIds) return
        mutable.update { it.copy(busy = true, message = null, messageScreen = MessageScreen.MANAGE) }
        work = viewModelScope.launch {
            try {
                val token = tokenForChannel(channelId)
                if (mutable.value.channels.none { it.id == channelId } || mutable.value.selectedId != selected.id) throw WrongChannel()
                val current = api.broadcast(token, selected.id)
                if (current.id != selected.id || current.channelId != channelId) throw WrongChannel()
                if (current.lifecycle != "live") throw StreamEnded()
                try { api.endBroadcast(token, selected.id) }
                catch (e: IOException) { /* The server may have accepted the request. */ }
                catch (e: IncompleteResponse) { /* Read back an ambiguous transition response. */ }
                catch (e: YouTubeFailure) { if (e.status !in 500..599 && e.reason != "redundantTransition") throw e }
                val verified = try { api.broadcast(token, selected.id) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { throw UncertainEnd() }
                if (verified.id != selected.id || verified.channelId != channelId) throw WrongChannel()
                if (verified.lifecycle != "complete") throw UncertainEnd()
                mutable.update { old -> old.copy(streams = old.streams.filterNot { it.id == selected.id }, selectedId = null, draft = "", editingBaseTitle = null, busy = false, loaded = true, message = STREAM_ENDED_MESSAGE) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleFailure(e, selected.channelId) }
        }
    }

    fun disconnect(channelId: String) {
        if (disconnecting || mutable.value.channels.none { it.id == channelId }) return
        disconnecting = true
        val previousWork = work
        val interrupted = mutable.value.busy
        val platform = mutable.value.channels.first { it.id == channelId }.platform
        if (platform == Platform.TWITCH) twitchSession.invalidate(channelId) else session.invalidate(channelId)
        mutable.update { old ->
            val selectionRemoved = old.streams.any { it.id == old.selectedId && it.channelId == channelId }
            old.copy(channels = old.channels.filterNot { it.id == channelId }, reconnectIds = old.reconnectIds - channelId,
                streams = old.streams.filterNot { it.channelId == channelId }, selectedId = if (selectionRemoved) null else old.selectedId,
                draft = if (selectionRemoved) "" else old.draft, editingBaseTitle = if (selectionRemoved) null else old.editingBaseTitle,
                conflictTitle = if (selectionRemoved) null else old.conflictTitle, busy = true, message = null, messageScreen = MessageScreen.ACCOUNTS)
        }
        work = viewModelScope.launch {
            previousWork?.cancelAndJoin()
            try {
                val revoked = if (platform == Platform.TWITCH) twitchSession.disconnect(channelId) else session.disconnect(channelId)
                val result = when {
                    platform == Platform.TWITCH && revoked -> "Twitch access token revoked."
                    platform == Platform.TWITCH -> "Disconnected locally, but Twitch could not confirm revocation. Remove access in Twitch settings."
                    revoked -> "YouTube access revoked. Other channels may need to reconnect."
                    else -> "Disconnected locally, but Google could not confirm revocation. Remove access in your Google Account settings."
                }
                mutable.update { it.copy(busy = false, message = result + if (interrupted) " An interrupted operation may have completed; refresh to check." else "", messageScreen = MessageScreen.ACCOUNTS) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(busy = false, message = "Could not fully remove the saved connection. Clear app data and remove access in your platform account settings.", messageScreen = MessageScreen.ACCOUNTS) } }
            finally { disconnecting = false }
        }
    }

    private suspend fun tokenForChannel(channelId: String): String {
        if (mutable.value.channels.none { it.id == channelId } || channelId in mutable.value.reconnectIds) throw AuthorizationRequired()
        val token = session.token(channelId)
        if (api.channel(token).id != channelId) throw WrongChannel()
        return token
    }

    private fun handleFailure(e: Exception, channelId: String) {
        val needsReconnect = e is AuthorizationRequired || e is TwitchReconnect || e is WrongChannel || (e is YouTubeFailure && e.status == 401) || (e is TwitchFailure && e.status == 401)
        mutable.update { old ->
            if (needsReconnect) old.copy(reconnectIds = old.reconnectIds + channelId,
                streams = old.streams.filterNot { it.channelId == channelId }, selectedId = null, draft = "", editingBaseTitle = null,
                busy = false, message = messageFor(e), messageScreen = MessageScreen.MANAGE)
            else old.copy(busy = false, message = messageFor(e), messageScreen = MessageScreen.MANAGE)
        }
    }

    private fun messageFor(e: Exception): String = when (e) {
        is TwitchReconnect -> "Twitch authorization is unavailable. Reconnect Twitch."
        is TwitchResponseInvalid -> "Twitch returned an incomplete response. Try again."
        is TwitchFailure -> when (e.status) {
            401 -> "Twitch authorization expired or was revoked. Reconnect Twitch."
            429 -> "Twitch rate limit reached. Try again later."
            else -> "Twitch could not complete the request. Please try again."
        }
        is NoYouTubeChannel -> NO_CHANNELS_RETURNED_MESSAGE
        is MultipleYouTubeChannels -> "YouTube returned multiple channels for this account. Stream Controls cannot safely choose one yet."
        is IncompleteResponse, is org.json.JSONException -> "YouTube returned an incomplete response. Please try again."
        is AuthorizationRequired -> "Authorization is unavailable. Reconnect YouTube."
        is WrongChannel -> "The authorized YouTube channel changed. Reconnect to review the channel before continuing."
        is StreamEnded -> "This stream is no longer active. Refresh the stream list."
        is UncertainUpdate -> "Couldn't check the updated title on YouTube. The update may have gone through; refresh on the Streams page or check YouTube before trying again."
        is UncertainEnd -> "YouTube has not confirmed that the livestream ended. Check YouTube or refresh before taking further action."
        is IOException -> "Network error. Check your connection and try again."
        is YouTubeFailure -> youtubeFailureMessage(e)
        is SecurityException -> "Android could not access the selected Google account. Choose it again and check Google Play services."
        else -> "Could not complete the YouTube request (error: ${safeErrorType(e)}). Check your account and connection."
    }
}

internal const val NO_CHANNELS_RETURNED_MESSAGE = "Google authorized the account, but YouTube returned no channels for that authorization. (YT-NO-CHANNELS)"
internal const val CHANNEL_REQUIRED_MESSAGE = "Google authorized the account, but YouTube says that authorization has no linked channel. (YT-CHANNEL-REQUIRED)"

internal fun youtubeFailureMessage(error: YouTubeFailure): String = when {
    error.reason == "youtubeSignupRequired" -> CHANNEL_REQUIRED_MESSAGE
    error.status == 401 -> "Authorization expired or was revoked. Reconnect YouTube."
    error.reason == "accessNotConfigured" || error.reason == "SERVICE_DISABLED" -> "The YouTube Data API is not enabled for this Google Cloud project. Enable it and try again."
    error.reason == "liveStreamingNotEnabled" -> "YouTube says live streaming is not enabled for this channel. Check the channel's live streaming eligibility in YouTube Studio."
    error.reason == "livePermissionBlocked" -> "YouTube says this channel cannot stream live right now. Check the channel's status in YouTube Studio."
    error.reason == "insufficientLivePermissions" -> "YouTube did not authorize access to this channel's live broadcasts. Check the selected channel and its permissions."
    error.reason == "quotaExceeded" || error.reason == "dailyLimitExceeded" -> "YouTube API quota is exhausted. Try again after it resets."
    error.reason == "userRequestsExceedRateLimit" || error.reason == "rateLimitExceeded" -> "YouTube is receiving too many requests. Please try again later."
    error.status == 412 -> "The video changed elsewhere. Refresh and review the title before updating."
    error.status == 403 -> "YouTube denied access. Check channel permissions and try again."
    error.status == 404 -> "The selected stream is no longer available."
    error.status == 429 -> "Too many requests. Please try again later."
    else -> "YouTube could not complete the request. Please try again."
}

// Expose only an exception type, never an exception message, response body or credential.
private fun safeErrorType(error: Throwable?): String = when (error) {
    is IllegalStateException -> "device state"
    is SecurityException -> "account access"
    is IOException -> "storage or network"
    is org.json.JSONException -> "response format"
    else -> "unexpected"
}
private class WrongChannel : Exception()
private class StreamEnded : Exception()
private class UncertainUpdate : Exception()
private class UncertainEnd : Exception()

/** Refresh preserves an in-progress edit and the remote title it was based on. */
fun reconcileStreams(old: StreamsState, list: List<LiveStream>): StreamsState {
    val previousSelection = old.streams.find { it.id == old.selectedId }
    val current = list.find { it.id == old.selectedId }
    val selected = current ?: if (list.size == 1) list.first() else null
    val preserveDraft = current != null && previousSelection != null && old.draft != previousSelection.title
    return old.copy(streams = list, selectedId = selected?.id,
        draft = if (preserveDraft) old.draft else selected?.title.orEmpty(),
        editingBaseTitle = if (preserveDraft) old.editingBaseTitle else selected?.title,
        busy = false, loaded = true, conflictTitle = null)
}


