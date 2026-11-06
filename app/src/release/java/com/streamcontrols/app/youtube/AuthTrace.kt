package com.streamcontrols.app.youtube

internal object AuthTrace {
    fun channelResponse(status: Int, reason: String?, count: Int?) = Unit
    fun apiFailure(path: String, status: Int, reason: String?) = Unit
    fun thumbnail(outcome: String, status: Int?) = Unit
}
