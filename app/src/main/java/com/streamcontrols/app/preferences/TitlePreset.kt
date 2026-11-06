package com.streamcontrols.app.preferences

/** Presets preserve the exact title entered by the user. */
fun titleCharacterCount(title: String): Int = title.codePointCount(0, title.length)

/** Reject incomplete UTF-16 pairs, which cannot represent valid Unicode text. */
fun hasValidUnicode(title: String): Boolean {
    var index = 0
    while (index < title.length) {
        val char = title[index]
        if (Character.isHighSurrogate(char)) {
            if (index + 1 >= title.length || !Character.isLowSurrogate(title[index + 1])) return false
            index += 2
        } else {
            if (Character.isLowSurrogate(char)) return false
            index++
        }
    }
    return true
}

// Presets are shared across platforms; validate against the selected platform when used.
fun isValidPreset(title: String): Boolean = title.isNotBlank() && hasValidUnicode(title) && titleCharacterCount(title) <= 140
