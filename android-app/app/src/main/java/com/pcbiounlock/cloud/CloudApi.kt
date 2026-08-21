package com.pcbiounlock.cloud

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CloudApi(private val baseUrl: String) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun call(path: String, method: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
        if(token != null) b.header("Authorization", "Bearer $token")
        when(method) {
            "GET" -> b.get()
            "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(jsonType))
            "DELETE" -> b.delete()
        }
        client.newCall(b.build()).execute().use { r ->
            val text = r.body.string(); val obj = if(text.isBlank()) JSONObject() else JSONObject(text)
            if(!r.isSuccessful) throw IllegalStateException(obj.optString("error", "HTTP ${r.code}"))
            return obj
        }
    }

    fun register(login: String, password: String) = call("/v1/auth/register", "POST", JSONObject().put("login", login).put("password", password))
    fun login(login: String, password: String) = call("/v1/auth/login", "POST", JSONObject().put("login", login).put("password", password))
    fun enrollPhone(accountToken: String, name: String, identity: CloudProtocol.Identity): JSONObject =
        call("/v1/devices", "POST", JSONObject().put("name", name), accountToken)
    fun devices(accountToken: String) = call("/v1/devices", "GET", token = accountToken)
    fun poll(accountToken: String, deviceId: String) = call("/v1/relay/devices/$deviceId/requests", "GET", token = accountToken)
    fun respond(accountToken: String, requestId: String, payload: String) =
        call("/v1/relay/requests/$requestId/response", "POST", JSONObject().put("payload", payload), accountToken)
    fun sendCommand(accountToken: String, deviceId: String, payload: String) =
        call("/v1/relay/devices/$deviceId/commands", "POST", JSONObject().put("payload", payload), accountToken)
}
