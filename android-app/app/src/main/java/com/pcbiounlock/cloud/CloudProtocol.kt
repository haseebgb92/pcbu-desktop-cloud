package com.pcbiounlock.cloud

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object CloudProtocol {
    const val VERSION = "pcbu-cloud-v1"
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    data class Identity(
        val signingPrivate: ByteArray,
        val signingPublic: ByteArray,
        val exchangePrivate: ByteArray,
        val exchangePublic: ByteArray
    )

    data class Peer(val deviceId: String, val signingPublicPem: String, val exchangePublicPem: String)

    fun generateIdentity(): Identity {
        val signPriv = Ed25519PrivateKeyParameters(random)
        val xPriv = X25519PrivateKeyParameters(random)
        return Identity(signPriv.encoded, signPriv.generatePublicKey().encoded, xPriv.encoded, xPriv.generatePublicKey().encoded)
    }

    fun ed25519PublicPem(raw: ByteArray): String = pem("PUBLIC KEY", hex("302a300506032b6570032100") + raw)
    fun x25519PublicPem(raw: ByteArray): String = pem("PUBLIC KEY", hex("302a300506032b656e032100") + raw)
    fun rawPublicFromPem(pem: String): ByteArray = b64d.decode(pem.lines().filterNot { it.startsWith("---") }.joinToString("")).takeLast(32).toByteArray()

    fun buildEnvelope(identity: Identity, localDeviceId: String, peer: Peer, intent: String, plaintext: String, nowMs: Long = System.currentTimeMillis()): JSONObject {
        val messageId = randomHex(16)
        val nonce = randomHex(32)
        val cipher = encrypt(identity.exchangePrivate, rawPublicFromPem(peer.exchangePublicPem), nonce, plaintext)
        val cipherText = JSONObject().put("iv", cipher.first).put("ciphertext", cipher.second).put("tag", cipher.third).toString()
        val expires = nowMs + 20_000
        val canonical = listOf(VERSION, messageId, peer.deviceId, intent, nowMs.toString(), expires.toString(), nonce, cipherText).joinToString("\n")
        val signature = sign(identity.signingPrivate, canonical.toByteArray())
        return JSONObject()
            .put("type", "relay")
            .put("message_id", messageId)
            .put("target_device_id", peer.deviceId)
            .put("intent", intent)
            .put("created_at_ms", nowMs)
            .put("expires_at_ms", expires)
            .put("nonce", nonce)
            .put("ciphertext", cipherText)
            .put("signature", b64.encodeToString(signature))
    }

    fun decryptEnvelope(identity: Identity, expectedLocalId: String, peer: Peer, obj: JSONObject, nowMs: Long = System.currentTimeMillis()): String {
        require(obj.getString("target_device_id") == expectedLocalId)
        require(obj.getString("source_device_id") == peer.deviceId)
        val created = obj.getLong("created_at_ms")
        val expires = obj.getLong("expires_at_ms")
        require(created > 0 && expires > nowMs && expires - created <= 60_000 && created <= nowMs + 30_000)
        val canonical = listOf(
            VERSION, obj.getString("message_id"), obj.getString("target_device_id"), obj.getString("intent"),
            created.toString(), expires.toString(), obj.getString("nonce"), obj.getString("ciphertext")
        ).joinToString("\n")
        require(verify(rawPublicFromPem(peer.signingPublicPem), canonical.toByteArray(), b64d.decode(obj.getString("signature"))))
        val c = JSONObject(obj.getString("ciphertext"))
        return decrypt(identity.exchangePrivate, rawPublicFromPem(peer.exchangePublicPem), obj.getString("nonce"),
            c.getString("iv"), c.getString("ciphertext"), c.getString("tag"))
    }

    private fun sign(privateRaw: ByteArray, data: ByteArray): ByteArray {
        val s = Ed25519Signer(); s.init(true, Ed25519PrivateKeyParameters(privateRaw, 0)); s.update(data, 0, data.size); return s.generateSignature()
    }
    private fun verify(publicRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        val s = Ed25519Signer(); s.init(false, Ed25519PublicKeyParameters(publicRaw, 0)); s.update(data, 0, data.size); return s.verifySignature(signature)
    }

    private fun shared(privateRaw: ByteArray, publicRaw: ByteArray): ByteArray {
        val agreement = X25519Agreement(); agreement.init(X25519PrivateKeyParameters(privateRaw, 0))
        val out = ByteArray(agreement.agreementSize); agreement.calculateAgreement(X25519PublicKeyParameters(publicRaw, 0), out, 0); return out
    }
    private fun key(privateRaw: ByteArray, publicRaw: ByteArray, nonce: String): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared(privateRaw, publicRaw), "pcbu-cloud-v1".toByteArray(), "pcbu-remote-command|$nonce".toByteArray()))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, it.size) }
    }
    private fun encrypt(privateRaw: ByteArray, publicRaw: ByteArray, nonce: String, plain: String): Triple<String, String, String> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key(privateRaw, publicRaw, nonce)), 128, iv, "pcbu-cloud-v1|$nonce".toByteArray()))
        val input = plain.toByteArray(); val out = ByteArray(cipher.getOutputSize(input.size)); var n = cipher.processBytes(input, 0, input.size, out, 0); n += cipher.doFinal(out, n)
        val data = out.copyOf(n); val ct = data.copyOfRange(0, data.size - 16); val tag = data.copyOfRange(data.size - 16, data.size)
        return Triple(b64.encodeToString(iv), b64.encodeToString(ct), b64.encodeToString(tag))
    }
    private fun decrypt(privateRaw: ByteArray, publicRaw: ByteArray, nonce: String, iv64: String, ct64: String, tag64: String): String {
        val iv = b64d.decode(iv64); val input = b64d.decode(ct64) + b64d.decode(tag64)
        val cipher = GCMBlockCipher(AESEngine())
        cipher.init(false, AEADParameters(KeyParameter(key(privateRaw, publicRaw, nonce)), 128, iv, "pcbu-cloud-v1|$nonce".toByteArray()))
        val out = ByteArray(cipher.getOutputSize(input.size)); var n = cipher.processBytes(input, 0, input.size, out, 0); n += cipher.doFinal(out, n)
        return out.copyOf(n).toString(Charsets.UTF_8)
    }

    private fun randomHex(bytes: Int) = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun pem(label: String, der: ByteArray): String {
        val body = b64.encodeToString(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$body\n-----END $label-----\n"
    }
}
