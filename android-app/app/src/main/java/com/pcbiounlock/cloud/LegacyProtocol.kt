package com.pcbiounlock.cloud

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object LegacyProtocol {
    const val PACKET_HEADER = 0xDB065AC7AFDFA4CCuL
    const val PAIR_INIT = 0x50
    const val PAIR_RESPONSE = 0x51
    const val DEVICE_ID = 0xB0
    const val UNLOCK_REQUEST = 0xB1
    const val UNLOCK_RESPONSE = 0xB2
    const val PAIR_VERSION = "4.0.0"
    const val UNLOCK_VERSION = "3.0.0"
    private const val TIMEOUT_MS = 120_000L
    private val random = SecureRandom()

    fun encryptPacket(plain: ByteArray, password: String, nowMs: Long = System.currentTimeMillis()): ByteArray {
        val input = ByteBuffer.allocate(8 + plain.size).putLong(nowMs).put(plain).array()
        val iv = ByteArray(16).also(random::nextBytes)
        val salt = ByteArray(16).also(random::nextBytes)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 65535, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + salt + cipher.doFinal(input)
    }

    fun decryptPacket(data: ByteArray, password: String, nowMs: Long = System.currentTimeMillis()): ByteArray {
        require(data.size >= 48)
        val iv = data.copyOfRange(0, 16); val salt = data.copyOfRange(16, 32); val enc = data.copyOfRange(32, data.size)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, 65535, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(enc)
        val bb = ByteBuffer.wrap(plain); val ts = bb.long
        require(kotlin.math.abs(nowMs - ts) <= TIMEOUT_MS)
        return ByteArray(plain.size - 8).also { bb.get(it) }
    }

    data class Packet(val id: Int, val data: ByteArray)

    fun writePacket(out: DataOutputStream, id: Int, data: ByteArray) {
        require(data.isNotEmpty() && data.size <= 65535)
        out.writeLong(PACKET_HEADER.toLong()); out.writeShort(id); out.writeShort(data.size); out.write(data); out.flush()
    }

    fun readPacket(input: DataInputStream): Packet {
        var matched = 0
        val header = ByteBuffer.allocate(8).putLong(PACKET_HEADER.toLong()).array()
        while (matched < header.size) {
            val b = input.readByte()
            matched = if (b == header[matched]) matched + 1 else 0
        }
        val id = input.readUnsignedShort(); val len = input.readUnsignedShort(); require(len > 0)
        return Packet(id, ByteArray(len).also(input::readFully))
    }

    fun pair(ip: String, port: Int, encKey: String, init: JSONObject): JSONObject {
        Socket(ip, port).use { socket ->
            socket.soTimeout = 20_000
            val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
            writePacket(out, PAIR_INIT, encryptPacket(init.toString().toByteArray(), encKey))
            val response = readPacket(input); require(response.id == PAIR_RESPONSE)
            return JSONObject(decryptPacket(response.data, encKey).toString(Charsets.UTF_8))
        }
    }

    fun answerUnlock(ip: String, port: Int, deviceId: String, encryptionKey: String, passwordKey: String) {
        Socket(ip, port).use { socket ->
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
            writePacket(out, DEVICE_ID, deviceId.toByteArray())
            val packet = readPacket(input); require(packet.id == UNLOCK_REQUEST)
            val req = JSONObject(packet.data.toString(Charsets.UTF_8))
            require(req.getString("protoVersion") == UNLOCK_VERSION && req.getString("deviceId") == deviceId)
            val requestData = JSONObject(decryptPacket(hexDecode(req.getString("encData")), encryptionKey).toString(Charsets.UTF_8))
            val result = JSONObject().put("unlockToken", requestData.getString("unlockToken")).put("passwordKey", passwordKey)
            val resp = JSONObject().put("error", "").put("encData", hexEncode(encryptPacket(result.toString().toByteArray(), encryptionKey)))
            writePacket(out, UNLOCK_RESPONSE, resp.toString().toByteArray())
        }
    }

    fun hexEncode(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun hexDecode(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
