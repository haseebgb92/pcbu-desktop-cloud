package com.pcbiounlock.cloud

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class PairingClient(private val context: Context, private val store: SecureStore) {
    data class Qr(val serverId: String?, val ip: String?, val port: Int, val method: String, val encKey: String)

    fun parseQr(text: String): Qr {
        val o = JSONObject(text)
        return Qr(o.optString("serverId").ifBlank { null }, o.optString("ip").ifBlank { null }, o.getInt("port"), o.getString("method"), o.getString("encKey"))
    }

    private fun localIpv4(): String {
        NetworkInterface.getNetworkInterfaces().toList().forEach { n ->
            n.inetAddresses.toList().forEach { a -> if(!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "" }
        }
        return ""
    }

    private fun discover(qr: Qr): Pair<String, Int> {
        qr.ip?.let { return it to qr.port }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("pcbu-pairing").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket(qr.port).use { socket ->
                socket.broadcast = true; socket.soTimeout = 15_000
                val buf = ByteArray(4096)
                while(true) {
                    val packet = DatagramPacket(buf, buf.size); socket.receive(packet)
                    val plain = runCatching { LegacyProtocol.decryptPacket(packet.data.copyOf(packet.length), qr.encKey) }.getOrNull() ?: continue
                    val beacon = runCatching { JSONObject(plain.toString(Charsets.UTF_8)) }.getOrNull() ?: continue
                    if(beacon.optString("serverId") == qr.serverId)
                        return beacon.getString("ip") to beacon.getInt("port")
                }
            }
        } finally { if(lock.isHeld) lock.release() }
    }

    fun pair(qrText: String, session: CloudSession, identity: CloudProtocol.Identity): PairedPc {
        val qr = parseQr(qrText)
        require(qr.method == "UDP" || qr.method == "MANUAL_UDP" || qr.method == "CLOUD_TCP") { "Unsupported pairing method." }
        val (ip, port) = discover(qr)
        val phoneUuid = store.get("phone_uuid") ?: UUID.randomUUID().toString().also { store.put("phone_uuid", it) }
        val init = JSONObject()
            .put("protoVersion", LegacyProtocol.PAIR_VERSION)
            .put("deviceUUID", phoneUuid)
            .put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            .put("ipAddress", localIpv4())
            .put("tcpPort", 0)
            .put("udpPort", 43300)
            .put("udpManualPort", 43301)
            .put("cloudToken", session.phoneDeviceToken)
            .put("cloudAccountId", session.accountId)
            .put("cloudDeviceId", session.phoneDeviceId)
            .put("cloudSigningPublicKey", CloudProtocol.ed25519PublicPem(identity.signingPublic))
            .put("cloudExchangePublicKey", CloudProtocol.x25519PublicPem(identity.exchangePublic))
        val response = LegacyProtocol.pair(ip, port, qr.encKey, init)
        val error = response.optString("errMsg")
        require(error.isBlank()) { error }
        val d = response.getJSONObject("data")
        return PairedPc(
            localDeviceId = d.getString("deviceId"),
            name = d.getString("deviceName"),
            userName = d.getString("userName"),
            encryptionKey = qr.encKey,
            passwordKey = d.getString("passwordKey"),
            cloudDeviceId = if(d.optString("cloudAccountId") == session.accountId) d.optString("cloudDeviceId") else "",
            cloudSigningPublicKey = d.optString("cloudSigningPublicKey"),
            cloudExchangePublicKey = d.optString("cloudExchangePublicKey"),
            udpPort = 43300
        )
    }
}
