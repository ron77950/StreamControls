package com.streamcontrols.app.youtube

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class DpopTest {
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
    private fun encode(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    @Test fun proofUsesCorrectClaimsAndVerifiableEs256Signature() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val proof = makeDpopProof(pair.private, pair.public as ECPublicKey, "example-code", "example-nonce", 12345)
        val parts = proof.split('.')
        assertEquals(3, parts.size)
        val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
        val payload = JSONObject(String(decode(parts[1]), Charsets.UTF_8))
        assertEquals("ES256", header.getString("alg"))
        assertEquals("dpop+jwt", header.getString("typ"))
        assertEquals("P-256", header.getJSONObject("jwk").getString("crv"))
        val jwk = header.getJSONObject("jwk")
        val publicKey = pair.public as ECPublicKey
        assertEquals(publicKey.w.affineX, BigInteger(1, decode(jwk.getString("x"))))
        assertEquals(publicKey.w.affineY, BigInteger(1, decode(jwk.getString("y"))))
        assertEquals(encode(MessageDigest.getInstance("SHA-256").digest("example-code".toByteArray())), payload.getString("jti"))
        assertEquals("POST", payload.getString("htm"))
        assertEquals("https://oauth2.googleapis.com/token", payload.getString("htu"))
        assertEquals("example-nonce", payload.getString("nonce"))
        assertEquals(12345L, payload.getLong("iat"))
        val raw = decode(parts[2])
        assertEquals(64, raw.size)
        fun derInteger(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(2, value.size.toByte()) + value
        }
        val integers = derInteger(raw.copyOfRange(0, 32)) + derInteger(raw.copyOfRange(32, 64))
        val der = byteArrayOf(0x30, integers.size.toByte()) + integers
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public); update((parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII))
        }
        assertTrue(verifier.verify(der))
        val first = JSONObject(String(decode(makeDpopProof(pair.private, pair.public as ECPublicKey, null, null).split('.')[1])))
        val second = JSONObject(String(decode(makeDpopProof(pair.private, pair.public as ECPublicKey, null, null).split('.')[1])))
        assertNotEquals(first.getString("jti"), second.getString("jti"))
    }

    @Test fun nonceChallengeRetriesOnceWithNewProof() {
        val proofs = mutableListOf<String?>()
        val keys = object : DpopKeys {
            override fun create() = "key"
            override fun exists(alias: String) = true
            override fun proof(alias: String, code: String?, nonce: String?): String { proofs.add(nonce); return "proof-${proofs.size}" }
            override fun delete(alias: String) = Unit
        }
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val number = calls.incrementAndGet()
            assertEquals("proof-$number", chain.request().header("DPoP"))
            assertEquals("oauth2.googleapis.com", chain.request().url.host)
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .message("test").code(if (number == 1) 400 else 200)
                .body((if (number == 1) """{"error":"use_dpop_nonce"}""" else """{"access_token":"fake"}""").toResponseBody("application/json".toMediaType()))
            builder.header("DPoP-Nonce", if (number == 1) "challenge" else "next").build()
        }.build()
        val result = DpopTokenClient(client, keys).exchange(FormBody.Builder().build(), "key", previousNonce = "old")
        assertEquals(listOf("old", "challenge"), proofs)
        assertEquals("next", result.nonce)
        assertEquals(2, calls.get())
    }

    @Test fun networkFailureIsNotRetried() {
        val calls = AtomicInteger()
        val keys = object : DpopKeys {
            override fun create() = "key"
            override fun exists(alias: String) = true
            override fun proof(alias: String, code: String?, nonce: String?) = "proof"
            override fun delete(alias: String) = Unit
        }
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); throw IOException("failure") }.build()
        try { DpopTokenClient(client, keys).exchange(FormBody.Builder().build(), "key"); fail("Expected failure") }
        catch (_: IOException) { }
        assertEquals(1, calls.get())
    }

    @Test fun repeatedNonceChallengesStopAfterOneRetry() {
        val calls = AtomicInteger()
        val keys = object : DpopKeys {
            override fun create() = "key"
            override fun exists(alias: String) = true
            override fun proof(alias: String, code: String?, nonce: String?) = "proof"
            override fun delete(alias: String) = Unit
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("test")
                .header("DPoP-Nonce", "new-nonce")
                .body("""{"error":"use_dpop_nonce"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try { DpopTokenClient(client, keys).exchange(FormBody.Builder().build(), "key"); fail("Expected failure") }
        catch (_: AuthorizationRequired) { }
        assertEquals(2, calls.get())
    }
}
