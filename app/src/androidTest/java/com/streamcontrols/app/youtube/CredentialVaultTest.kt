package com.streamcontrols.app.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    @Test fun dpopKeyIsStoredInAndroidKeystoreAndCanBeRemoved() {
        val alias = AndroidDpopKeys.create()
        try {
            assertTrue(AndroidDpopKeys.exists(alias))
            assertEquals(3, AndroidDpopKeys.proof(alias, "test-code", null).split('.').size)
            val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(store.getKey(alias, null).encoded)
        } finally { AndroidDpopKeys.delete(alias) }
        assertFalse(AndroidDpopKeys.exists(alias))
    }

    @Test fun encryptedRecordRestoresAndRejectsTamperingAndOtherClients() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = UUID.randomUUID().toString()
        val path = context.cacheDir.resolve("vault-$unique")
        val alias = "streamcontrol.test.$unique"
        val vault = CredentialVault(path, alias, "client-a")
        try {
            vault.persist(SavedCredential("fake-refresh-token", Channel("brand", "Test"), "key-alias"))
            assertFalse(String(path.readBytes(), Charsets.ISO_8859_1).contains("fake-refresh-token"))
            assertEquals(SavedCredential("fake-refresh-token", Channel("brand", "Test"), "key-alias"), vault.read())
            try { CredentialVault(path, alias, "client-b").read(); fail("Wrong client accepted") }
            catch (_: AuthorizationRequired) { }
            val bytes = path.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); path.writeBytes(bytes)
            try { vault.read(); fail("Tampered credential accepted") }
            catch (_: javax.crypto.AEADBadTagException) { }
        } finally { vault.clear() }
        assertFalse(path.exists())
    }
}
