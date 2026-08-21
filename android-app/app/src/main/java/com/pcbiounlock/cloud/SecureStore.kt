package com.pcbiounlock.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pcbu_secure", Context.MODE_PRIVATE)
    private val alias = "pcbu-cloud-store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }
    fun get(name: String): String? = runCatching {
        val raw = Base64.decode(prefs.getString(name, null), Base64.NO_WRAP); val iv = raw.copyOfRange(0, 12); val enc = raw.copyOfRange(12, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)); c.doFinal(enc).toString(Charsets.UTF_8)
    }.getOrNull()
    fun remove(name: String) = prefs.edit().remove(name).apply()

    fun identity(): CloudProtocol.Identity {
        get("identity")?.let { s ->
            val o = JSONObject(s)
            return CloudProtocol.Identity(
                Base64.decode(o.getString("sp"), Base64.NO_WRAP), Base64.decode(o.getString("sP"), Base64.NO_WRAP),
                Base64.decode(o.getString("xp"), Base64.NO_WRAP), Base64.decode(o.getString("xP"), Base64.NO_WRAP))
        }
        val id = CloudProtocol.generateIdentity()
        put("identity", JSONObject().put("sp", Base64.encodeToString(id.signingPrivate, Base64.NO_WRAP))
            .put("sP", Base64.encodeToString(id.signingPublic, Base64.NO_WRAP))
            .put("xp", Base64.encodeToString(id.exchangePrivate, Base64.NO_WRAP))
            .put("xP", Base64.encodeToString(id.exchangePublic, Base64.NO_WRAP)).toString())
        return id
    }

    fun savePairs(pairs: List<PairedPc>) {
        val a = JSONArray(); pairs.forEach { p -> a.put(JSONObject().put("localDeviceId", p.localDeviceId).put("name", p.name).put("userName", p.userName)
            .put("encryptionKey", p.encryptionKey).put("passwordKey", p.passwordKey).put("cloudDeviceId", p.cloudDeviceId)
            .put("cloudSigningPublicKey", p.cloudSigningPublicKey).put("cloudExchangePublicKey", p.cloudExchangePublicKey).put("udpPort", p.udpPort)) }
        put("pairs", a.toString())
    }
    fun pairs(): MutableList<PairedPc> {
        val text = get("pairs") ?: return mutableListOf(); val a = JSONArray(text); val out = mutableListOf<PairedPc>()
        for(i in 0 until a.length()) { val o=a.getJSONObject(i); out += PairedPc(o.getString("localDeviceId"),o.getString("name"),o.getString("userName"),o.getString("encryptionKey"),o.getString("passwordKey"),o.optString("cloudDeviceId"),o.optString("cloudSigningPublicKey"),o.optString("cloudExchangePublicKey"),o.optInt("udpPort",43300)) }
        return out
    }
}
