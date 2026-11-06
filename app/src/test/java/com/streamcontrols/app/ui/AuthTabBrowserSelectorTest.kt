package com.streamcontrols.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthTabBrowserSelectorTest {
    @Test fun choosesChromeEvenWhenAnotherBrowserIsDefault() {
        assertEquals(CHROME_PACKAGE, selectAuthTabBrowser({ CHROME_PACKAGE }, { "other.browser" }) { true })
    }

    @Test fun usesCompatibleDefaultWhenChromeIsUnavailable() {
        assertEquals("other.browser", selectAuthTabBrowser({ null }, { "other.browser" }) { it == "other.browser" })
    }

    @Test fun skipsChromeWhenItIsDisabledOrLacksAuthTab() {
        assertEquals("other.browser", selectAuthTabBrowser({ CHROME_PACKAGE }, { "other.browser" }) { it != CHROME_PACKAGE })
    }

    @Test fun returnsNullIfNoCompatibleBrowserExists() {
        assertNull(selectAuthTabBrowser({ CHROME_PACKAGE }, { "other.browser" }) { false })
        assertNull(selectAuthTabBrowser({ null }, { null }) { true })
    }

    @Test fun handlesProviderFailuresWithoutCrashing() {
        assertEquals("other.browser", selectAuthTabBrowser({ throw SecurityException() }, { "other.browser" }) { true })
        assertEquals("other.browser", selectAuthTabBrowser({ CHROME_PACKAGE }, { "other.browser" }) {
            if (it == CHROME_PACKAGE) throw SecurityException() else true
        })
        assertNull(selectAuthTabBrowser({ null }, { throw SecurityException() }) { true })
    }

    @Test fun doesNotTreatAnotherPackageAsChrome() {
        assertEquals("preferred.browser", selectAuthTabBrowser({ "unexpected.browser" }, { "preferred.browser" }) { true })
    }
}
