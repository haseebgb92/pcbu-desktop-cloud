package com.pcbiounlock.cloud

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections

class CloudController(
    private val api: CloudApi,
    private val store: SecureStore,
    private val session: CloudSession,
    private val identity: CloudProtocol.Identity,
    private val onState: (Boolean, String) -> Unit,
    private val onChallenge: (PairedPc, JSONObject) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    fun connect() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread {
            onState(true, "Cloud connected")
            while (running.get()) {
                try {
                    val request = api.poll(session.accountToken, session.phoneDeviceId)
                    if (request.has("id")) handle(request)
                    Thread.sleep(750)
                } catch (e: Exception) {
                    if (running.get()) onState(false, e.message ?: "Cloud error")
                    try { Thread.sleep(2_000) } catch (_: InterruptedException) { }
                }
            }
        }.apply { name = "pcbu-cloud-poll"; isDaemon = true; start() }
    }

    fun close() { running.set(false); worker?.interrupt(); worker = null }

    private fun handle(wrapper: JSONObject) {
        val requestId = wrapper.getString("id")
        if (!seen.add(requestId)) return
        while (seen.size > 128) seen.remove(seen.first())
        val outer = JSONObject(wrapper.getString("payload"))
        val pc = store.pairs().firstOrNull { it.localDeviceId == outer.optString("deviceId") } ?: return
        val encrypted = LegacyProtocol.hexDecode(outer.getString("encData"))
        val body = JSONObject(LegacyProtocol.decryptPacket(encrypted, pc.encryptionKey).toString(Charsets.UTF_8))
        body.put("cloudRequestId", requestId)
        onChallenge(pc, body)
    }

    fun approve(pc: PairedPc, challenge: JSONObject): Boolean {
        val data = JSONObject().put("unlockToken", challenge.getString("unlockToken")).put("passwordKey", pc.passwordKey)
        val encrypted = LegacyProtocol.encryptPacket(data.toString().toByteArray(), pc.encryptionKey)
        val response = JSONObject().put("error", "").put("encData", LegacyProtocol.hexEncode(encrypted))
        api.respond(session.accountToken, challenge.getString("cloudRequestId"), response.toString())
        return true
    }

    fun deny(pc: PairedPc, challenge: JSONObject, reason: String): Boolean {
        api.respond(session.accountToken, challenge.getString("cloudRequestId"), JSONObject().put("error", "CANCEL").put("encData", "").toString())
        return true
    }
}
