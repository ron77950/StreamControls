package com.streamcontrols.app.youtube

import com.streamcontrols.app.preferences.hasValidUnicode
import com.streamcontrols.app.preferences.titleCharacterCount

fun titleLimit(platform: Platform): Int = if (platform == Platform.TWITCH) 140 else 100

fun streamTitleError(title: String, platform: Platform): String? = when {
    title.isBlank() -> "Enter a title."
    !hasValidUnicode(title) -> "The title contains invalid characters."
    titleCharacterCount(title) > titleLimit(platform) -> "Title exceeds the ${titleLimit(platform)}-character limit."
    platform == Platform.YOUTUBE && ('<' in title || '>' in title) -> "YouTube titles cannot contain < or >."
    else -> null
}

fun isValidStreamTitle(title: String, platform: Platform): Boolean = streamTitleError(title, platform) == null
