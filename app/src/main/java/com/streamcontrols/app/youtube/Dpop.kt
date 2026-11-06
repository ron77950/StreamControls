package com.streamcontrols.app.youtube

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
private const val KEY_PREFIX = "streamcontrol.dpop."
private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
private fun randomId(): String = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })

internal interface DpopKeys {
    fun create(): String
    fun exists(alias: String): Boolean
    fun proof(alias: String, code: String?, nonce: String?): String
    fun delete(alias: String)
}

internal object AndroidDpopKeys : DpopKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun valid(alias: String) = alias.startsWith(KEY_PREFIX) && alias.removePrefix(KEY_PREFIX).matches(Regex("[A-Za-z0-9_-]{43}"))
    override fun create(): String {
        val alias = KEY_PREFIX + randomId()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
        return alias
    }
    override fun exists(alias: String) = valid(alias) && store().containsAlias(alias)
    override fun proof(alias: String, code: String?, nonce: String?): String {
        require(valid(alias))
        val keys = store()
        val privateKey = keys.getKey(alias, null) as? PrivateKey ?: throw AuthorizationRequired()
        val publicKey = keys.getCertificate(alias)?.publicKey as? ECPublicKey ?: throw AuthorizationRequired()
        return makeDpopProof(privateKey, publicKey, code, nonce)
    }
    override fun delete(alias: String) { if (valid(alias)) store().deleteEntry(alias) }
}

/** Google requires a code hash for the initial exchange and a fresh ID for refresh. */
internal fun makeDpopProof(privateKey: PrivateKey, publicKey: ECPublicKey, code: String?, nonce: String?, now: Long = System.currentTimeMillis() / 1000): String {
    fun coordinate(value: BigInteger): String {
        val raw = value.toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
        require(raw.size <= 32 && value.signum() >= 0)
        return b64(ByteArray(32 - raw.size) + raw)
    }
    val jwk = JSONObject().put("kty", "EC").put("crv", "P-256")
        .put("x", coordinate(publicKey.w.affineX)).put("y", coordinate(publicKey.w.affineY))
    val header = JSONObject().put("typ", "dpop+jwt").put("alg", "ES256").put("jwk", jwk)
    val jti = code?.let { b64(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))) } ?: randomId()
    val payload = JSONObject().put("jti", jti).put("htm", "POST").put("htu", TOKEN_ENDPOINT).put("iat", now)
    if (nonce != null) payload.put("nonce", nonce)
    val input = b64(header.toString().toByteArray(Charsets.UTF_8)) + "." + b64(payload.toString().toByteArray(Charsets.UTF_8))
    val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey); update(input.toByteArray(Charsets.US_ASCII)) }
    return input + "." + b64(derToJose(signer.sign()))
}

/** Android's ECDSA signature is ASN.1 DER; JWS ES256 requires 32-byte R and S. */
internal fun derToJose(der: ByteArray): ByteArray {
    require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2)
    var pos = 2
    fun integer(): ByteArray {
        require(pos + 2 <= der.size && der[pos++] == 0x02.toByte())
        val length = der[pos++].toInt() and 255
        require(length in 1..33 && pos + length <= der.size)
        val value = der.copyOfRange(pos, pos + length); pos += length
        require(value[0].toInt() and 0x80 == 0)
        val raw = if (value.size > 1 && value[0] == 0.toByte()) {
            require(value[1].toInt() and 0x80 != 0)
            value.copyOfRange(1, value.size)
        } else value
        require(raw.size <= 32)
        return ByteArray(32 - raw.size) + raw
    }
    val r = integer(); val s = integer()
    require(pos == der.size)
    return r + s
}

internal data class TokenResponse(val json: JSONObject, val nonce: String?)

internal class DpopTokenClient(client: OkHttpClient, private val keys: DpopKeys = AndroidDpopKeys) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    fun exchange(body: FormBody, alias: String, code: String? = null, previousNonce: String? = null): TokenResponse {
        var nonce = previousNonce
        repeat(2) { attempt ->
            val proof = keys.proof(alias, code, nonce)
            val request = Request.Builder().url(TOKEN_ENDPOINT).header("DPoP", proof).post(body).build()
            http.newCall(request).execute().use { response ->
                val receivedNonce = response.header("DPoP-Nonce")?.takeIf { it.length in 1..1024 }
                if (response.code == 429 || response.code in 500..599) throw IOException("Authorization service unavailable")
                val json = try { JSONObject(response.body?.string() ?: "") } catch (_: Exception) { throw AuthorizationRequired() }
                if (response.isSuccessful) return TokenResponse(json, receivedNonce ?: nonce)
                if (response.code == 400 && json.optString("error") == "use_dpop_nonce" && receivedNonce != null && attempt == 0) {
                    nonce = receivedNonce
                    return@use
                }
                throw AuthorizationRequired()
            }
        }
        throw AuthorizationRequired()
    }
}
