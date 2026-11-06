package com.streamcontrols.app.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

internal sealed interface TitleConfirmation {
    data object Confirmed : TitleConfirmation
    data class Different(val title: String) : TitleConfirmation
    data object Unavailable : TitleConfirmation
    data object WrongIdentity : TitleConfirmation
    data object AuthorizationExpired : TitleConfirmation
}

/** Re-read only; never retry the write. */
internal suspend fun confirmTitle(
    videoId: String,
    channelId: String,
    desired: String,
    read: suspend () -> VideoTitle,
    pause: suspend (Long) -> Unit = { delay(it) }
): TitleConfirmation {
    var lastTitle: String? = null
    var latestReadSucceeded = false
    for (wait in listOf(750L, 750L, 750L)) {
        pause(wait)
        try {
            val result = read()
            if (result.id != videoId || result.channelId != channelId) return TitleConfirmation.WrongIdentity
            if (result.title == desired) return TitleConfirmation.Confirmed
            lastTitle = result.title
            latestReadSucceeded = true
        } catch (e: CancellationException) { throw e }
        catch (e: YouTubeFailure) {
            latestReadSucceeded = false
            if (e.status == 401 && e.reason != "youtubeSignupRequired") return TitleConfirmation.AuthorizationExpired
            if (e.status != 429 && e.status !in 500..599) break
        } catch (_: IOException) { latestReadSucceeded = false }
        catch (_: IncompleteResponse) { latestReadSucceeded = false }
        catch (_: org.json.JSONException) { latestReadSucceeded = false }
        catch (_: Exception) { latestReadSucceeded = false; break }
    }
    return if (latestReadSucceeded) lastTitle?.let(TitleConfirmation::Different) ?: TitleConfirmation.Unavailable else TitleConfirmation.Unavailable
}
