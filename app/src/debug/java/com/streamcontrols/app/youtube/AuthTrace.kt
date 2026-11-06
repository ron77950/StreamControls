package com.streamcontrols.app.youtube

import android.util.Log

/** Whitelisted diagnostic fields only; never accepts credentials or response bodies. */
internal object AuthTrace {
    private const val TAG = "StreamControlAuth"
    private val knownReasons = setOf("youtubeSignupRequired", "authError", "insufficientPermissions", "insufficientLivePermissions", "liveStreamingNotEnabled", "livePermissionBlocked", "forbidden", "accessNotConfigured", "SERVICE_DISABLED", "quotaExceeded", "dailyLimitExceeded", "userRequestsExceedRateLimit")

    fun channelResponse(status: Int, reason: String?, count: Int?) {
        val safeReason = reason?.takeIf { it in knownReasons } ?: "other_or_none"
        Log.d(TAG, "channel_lookup status=$status reason=$safeReason count=${count ?: -1}")
    }
    fun apiFailure(path: String, status: Int, reason: String?) {
        val safePath = path.takeIf { it in setOf("channels", "liveBroadcasts", "videos") } ?: "other"
        val safeReason = reason?.takeIf { it in knownReasons } ?: "other_or_none"
        Log.d(TAG, "api_failure endpoint=$safePath status=$status reason=$safeReason")
    }
    fun thumbnail(outcome: String, status: Int?) {
        val safeOutcome = outcome.takeIf { it in setOf("missing", "http_error", "loaded", "unavailable", "network_error") } ?: "other"
        Log.d(TAG, "thumbnail outcome=$safeOutcome status=${status ?: -1}")
    }
}
