package com.streamcontrols.app.preferences

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPreferenceTest {
    @Test fun accountConnectionPreferencePersistsAcrossInstances() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = AppPreferences(context)
        val previous = preferences.data.first().accountConnectionNotifications
        try {
            preferences.setAccountConnectionNotifications(false)
            assertEquals(false, AppPreferences(context).data.first().accountConnectionNotifications)
            preferences.setAccountConnectionNotifications(true)
            assertEquals(true, AppPreferences(context).data.first().accountConnectionNotifications)
        } finally {
            preferences.setAccountConnectionNotifications(previous)
        }
    }
}
