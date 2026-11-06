package com.streamcontrols.app

import com.streamcontrols.app.youtube.Platform
import com.streamcontrols.app.youtube.isValidStreamTitle
import com.streamcontrols.app.youtube.streamTitleError
import org.junit.Assert.*
import org.junit.Test

class StreamTitleRulesTest {
    @Test fun youtubeAcceptsValidUnicodeAndRejectsItsDocumentedLimits() {
        assertTrue(isValidStreamTitle("😀".repeat(100), Platform.YOUTUBE))
        assertTrue(isValidStreamTitle("  my title  ", Platform.YOUTUBE))
        assertFalse(isValidStreamTitle("😀".repeat(101), Platform.YOUTUBE))
        assertFalse(isValidStreamTitle("invalid < title", Platform.YOUTUBE))
        assertFalse(isValidStreamTitle("invalid > title", Platform.YOUTUBE))
        assertFalse(isValidStreamTitle("\uD800", Platform.YOUTUBE))
        assertFalse(isValidStreamTitle("   ", Platform.YOUTUBE))
    }

    @Test fun twitchUsesItsOwnLimitWithoutYoutubesCharacterRestriction() {
        assertTrue(isValidStreamTitle("😀".repeat(140), Platform.TWITCH))
        assertTrue(isValidStreamTitle("title <with> symbols", Platform.TWITCH))
        assertFalse(isValidStreamTitle("😀".repeat(141), Platform.TWITCH))
        assertFalse(isValidStreamTitle("\uDC00", Platform.TWITCH))
        assertFalse(isValidStreamTitle("", Platform.TWITCH))
        assertEquals("Title exceeds the 140-character limit.", streamTitleError("a".repeat(141), Platform.TWITCH))
    }
}
