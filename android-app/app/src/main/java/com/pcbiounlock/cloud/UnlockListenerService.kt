package com.pcbiounlock.cloud

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.Executors

class UnlockListenerService : Service() {
    private val pool = Executors.newCachedThreadPool()
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var running = true
    private var cloudController: CloudController? = null

    override fun onCreate() {
        super.onCreate()
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createMulticastLock("pcbu-unlock").apply { setReferenceCounted(false); acquire() }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pcbu", "PC Bio Unlock", NotificationManager.IMPORTANCE_HIGH))
        startForeground(41, NotificationCompat.Builder(this, "pcbu").setContentTitle("PC Bio Unlock")
            .setContentText("Ready for fingerprint or face unlock").setSmallIcon(R.drawable.app_logo).setOngoing(true).build())
        pool.submit { listen(43300) }
        pool.submit { listen(43301) }
        startCloudListener()
    }

    private fun startCloudListener() {
        val store = SecureStore(this)
        val base = store.get("base_url") ?: return
        val saved = store.get("cloud_session")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val session = runCatching { CloudSession(saved.getString("accountToken"), saved.getString("accountId"), saved.getString("phoneDeviceId"), saved.getString("phoneDeviceToken")) }.getOrNull() ?: return
        cloudController = CloudController(CloudApi(base), store, session, store.identity(), { _, _ -> }, { pc, challenge ->
            store.put("cloud_pending", JSONObject().put("deviceId", pc.localDeviceId).put("challenge", challenge.toString()).toString())
            val launch = Intent(this, MainActivity::class.java).putExtra("approve_cloud", true)
            val pi = PendingIntent.getActivity(this, 43, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            getSystemService(NotificationManager::class.java).notify(43,
                NotificationCompat.Builder(this, "pcbu").setContentTitle("Remote unlock ${pc.name}?")
                    .setContentText("Tap to confirm with fingerprint or face").setSmallIcon(R.drawable.app_logo)
                    .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).build())
        }).also { it.connect() }
    }

    private fun listen(port: Int) {
        runCatching {
            DatagramSocket(port).use { socket ->
                socket.broadcast = true
                val buf = ByteArray(4096)
                while(running) {
                    val packet = DatagramPacket(buf, buf.size); socket.receive(packet)
                    val o = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    val pc = SecureStore(this).pairs().firstOrNull { it.localDeviceId == o.optString("deviceId") } ?: continue
                    val pending = JSONObject().put("deviceId", pc.localDeviceId).put("pcbuIP", o.getString("pcbuIP"))
                        .put("pcbuPort", o.getInt("pcbuPort")).put("expiresAtMs", System.currentTimeMillis() + 30_000)
                    SecureStore(this).put("local_pending", pending.toString())
                    val launch = Intent(this, MainActivity::class.java).putExtra("approve_local", true)
                    val pi = PendingIntent.getActivity(this, 42, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    getSystemService(NotificationManager::class.java).notify(42,
                        NotificationCompat.Builder(this, "pcbu").setContentTitle("Unlock ${pc.name}?")
                            .setContentText("Tap to confirm with fingerprint or face").setSmallIcon(R.drawable.app_logo)
                            .setAutoCancel(true).setContentIntent(pi).setPriority(NotificationCompat.PRIORITY_HIGH).build())
                }
            }
        }
    }

    override fun onDestroy() { running = false; cloudController?.close(); multicastLock?.let { if(it.isHeld) it.release() }; pool.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
