package com.streamcontrols.app

import com.streamcontrols.app.preferences.isValidPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitlePresetTest {
    @Test fun rejectsBlankAndTooLongTitles() {
        assertFalse(isValidPreset(""))
        assertFalse(isValidPreset("   "))
        assertFalse(isValidPreset("a".repeat(141)))
        assertFalse(isValidPreset("\uD800"))
    }
    @Test fun acceptsUnicodeAndBoundaryLength() {
        assertTrue(isValidPreset(" stream title "))
        assertTrue(isValidPreset("é".repeat(140)))
        assertTrue(isValidPreset("\uD83D\uDE00".repeat(140)))
        assertTrue(isValidPreset("Twitch <title>"))
        assertFalse(isValidPreset("\uD83D\uDE00".repeat(141)))
    }
}
