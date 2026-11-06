package com.streamcontrols.app

import com.streamcontrols.app.youtube.MessageScreen
import com.streamcontrols.app.youtube.StreamsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageScreenTest {
    @Test fun accountAndManagementMessagesStayOnTheirScreens() {
        val account = StreamsState(message = "Disconnected", messageScreen = MessageScreen.ACCOUNTS)
        assertEquals("Disconnected", account.messageFor(MessageScreen.ACCOUNTS))
        assertNull(account.messageFor(MessageScreen.STREAMS))
        assertNull(account.messageFor(MessageScreen.MANAGE))
        val management = StreamsState(message = "Title updated", messageScreen = MessageScreen.MANAGE)
        assertNull(management.messageFor(MessageScreen.STREAMS))
        assertNull(management.messageFor(MessageScreen.ACCOUNTS))
        assertEquals("Title updated", management.messageFor(MessageScreen.MANAGE))
    }

    @Test fun sharedWarningsRemainVisible() {
        val warning = StreamsState(message = "Reconnect", messageScreen = MessageScreen.ALL)
        assertEquals("Reconnect", warning.messageFor(MessageScreen.STREAMS))
        assertEquals("Reconnect", warning.messageFor(MessageScreen.ACCOUNTS))
    }
}
