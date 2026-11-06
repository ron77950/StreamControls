package com.streamcontrols.app.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class BrowserAttemptTest {
    private val client = "123-example.apps.googleusercontent.com"

    @Test fun authorizationHasFreshStateAndS256Challenge() {
        val first = BrowserAttempt.create(client)
        val second = BrowserAttempt.create(client)
        val request = first.authorizationUri()
        assertEquals("https", request.scheme)
        assertEquals("accounts.google.com", request.host)
        assertEquals("S256", request.getQueryParameter("code_challenge_method"))
        val hash = MessageDigest.getInstance("SHA-256").digest(first.verifier.toByteArray(Charsets.US_ASCII))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(hash), request.getQueryParameter("code_challenge"))
        assertNotEquals(request.getQueryParameter("state"), second.authorizationUri().getQueryParameter("state"))
        assertNotEquals(first.verifier, second.verifier)
    }

    @Test fun callbackRequiresExactRedirectAndSingleMatchingStateAndCode() {
        val attempt = BrowserAttempt.create(client)
        val state = attempt.authorizationUri().getQueryParameter("state")!!
        fun callback(base: String = attempt.redirect, query: String) = Uri.parse("$base?$query")
        assertEquals("one", attempt.validatedCode(callback(query = "state=$state&code=one")))
        assertNull(attempt.validatedCode(callback(query = "state=wrong&code=one")))
        assertNull(attempt.validatedCode(callback(query = "state=$state&state=$state&code=one")))
        assertNull(attempt.validatedCode(callback(query = "state=$state&code=one&code=two")))
        assertNull(attempt.validatedCode(callback(query = "state=$state&code=one&error=access_denied")))
        assertNull(attempt.validatedCode(callback(base = "${attempt.scheme}:/wrong", query = "state=$state&code=one")))
    }
}
