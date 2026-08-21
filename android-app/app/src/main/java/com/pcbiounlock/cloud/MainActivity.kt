package com.pcbiounlock.cloud

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var store: SecureStore
    private lateinit var root: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private var api: CloudApi? = null
    private var session: CloudSession? = null
    private var controller: CloudController? = null

    private val qrLauncher = registerForActivityResult(ScanContract()) { r -> r.contents?.let(::pairPc) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = SecureStore(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP; setPadding(36,36,36,36) }
        setContentView(ScrollView(this).apply { addView(root) })
        restoreSession()
        render()
        if(Build.VERSION.SDK_INT >= 33) root.post { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if(intent.getBooleanExtra("approve_local", false)) approvePendingLocal()
        if(intent.getBooleanExtra("approve_cloud", false)) approvePendingCloud()
    }

    override fun onDestroy() { controller?.close(); io.shutdownNow(); super.onDestroy() }

    private fun text(value: String, size: Float = 16f) = TextView(this).apply { this.text=value; textSize=size; setPadding(0,8,0,8) }
    private fun field(hint: String, secret: Boolean=false) = EditText(this).apply {
        this.hint=hint
        if(secret) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun button(label: String, f:()->Unit)=Button(this).apply { text=label; setOnClickListener{f()} }
    private fun toast(v:String)=Toast.makeText(this,v,Toast.LENGTH_LONG).show()

    private fun render() {
        root.removeAllViews(); root.addView(text("PC Bio Unlock Cloud",26f))
        val s=session
        if(s==null) renderLogin() else renderDashboard(s)
    }

    private fun renderLogin() {
        val defaultRelay = "https://pcbu-relay.advsolar.workers.dev"
        val url=field(defaultRelay).apply{setText(store.get("base_url")?:defaultRelay)}
        val email=field("User ID"); val password=field("Password (12+ characters)",true)
        root.addView(url);root.addView(email);root.addView(password)
        root.addView(button("Log in"){login(url.text.toString(),email.text.toString(),password.text.toString(),false)})
        root.addView(button("Create profile"){login(url.text.toString(),email.text.toString(),password.text.toString(),true)})
    }

    private fun login(base:String,email:String,password:String,register:Boolean){
        if(!base.startsWith("https://")||email.trim().length<3||password.length<12){toast("Use a 3+ character ID and a 12+ character password");return}
        io.submit { runCatching {
            val a=CloudApi(base.trimEnd('/'))
            if(register) runCatching{a.register(email,password)}.getOrElse{if(!it.message.orEmpty().contains("account_exists")) throw it}
            val l=a.login(email,password); val accountId=l.getString("accountId"); val token=l.getString("token"); val identity=store.identity()
            val old=store.get("phone_cloud_device")?.let{JSONObject(it)}
            val d=if(old?.optString("accountId")==accountId) old else {
                val e=a.enrollPhone(token,Settings.Global.getString(contentResolver,Settings.Global.DEVICE_NAME)?:Build.MODEL,identity)
                JSONObject().put("accountId",accountId).put("deviceId",e.getString("id")).put("deviceToken",e.getString("deviceToken")).also{store.put("phone_cloud_device",it.toString())}
            }
            val s=CloudSession(token,accountId,d.getString("deviceId"),d.getString("deviceToken"))
            store.put("base_url",base.trimEnd('/')); store.put("cloud_session",JSONObject().put("accountToken",token).put("accountId",accountId).put("phoneDeviceId",s.phoneDeviceId).put("phoneDeviceToken",s.phoneDeviceToken).toString())
            runOnUiThread{api=a;session=s;startCloud();startListener();render()}
        }.onFailure{runOnUiThread{toast(it.message?:"Login failed")}} }
    }

    private fun restoreSession(){
        val base=store.get("base_url")?:return; val o=store.get("cloud_session")?.let{runCatching{JSONObject(it)}.getOrNull()}?:return
        session=runCatching{CloudSession(o.getString("accountToken"),o.getString("accountId"),o.getString("phoneDeviceId"),o.getString("phoneDeviceToken"))}.getOrNull()
        api=CloudApi(base); if(session!=null){startCloud();startListener()}
    }

    private fun startListener(){ContextCompat.startForegroundService(this,Intent(this,UnlockListenerService::class.java))}

    private fun startCloud(){
        val a=api?:return;val s=session?:return;controller?.close()
        controller=CloudController(a,store,s,store.identity(),
            {online,detail->runOnUiThread{if(!online) toast(detail)}},
            {pc,challenge->runOnUiThread{biometric("Unlock ${pc.name}"){ok->if(ok)controller?.approve(pc,challenge)else controller?.deny(pc,challenge,"biometric_canceled")}}})
        // The foreground service owns the polling loop so requests continue while this activity is closed.
    }

    private fun renderDashboard(s:CloudSession){
        root.addView(text("Profile connected",18f));root.addView(text("Phone ${s.phoneDeviceId.take(10)}…"))
        root.addView(button("Pair a PC by QR"){qrLauncher.launch(ScanOptions().setPrompt("Scan the QR shown by PC Bio Unlock").setBeepEnabled(false))})
        root.addView(button("Log out"){controller?.close();controller=null;stopService(Intent(this,UnlockListenerService::class.java));session=null;store.remove("cloud_session");render()})
        root.addView(text("My systems",20f))
        val pcs=store.pairs();if(pcs.isEmpty())root.addView(text("No PC paired yet."))
        pcs.forEach{pc->
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,18,18,18)}
            box.addView(text("${pc.name} • ${pc.userName}",18f));box.addView(text(if(pc.cloudDeviceId.isBlank())"Local only — re-pair after enabling PC Cloud Profile" else "Remote ready"))
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(button("Remote ready"){toast("Lock the PC normally, then approve its biometric request here.")},LinearLayout.LayoutParams(0,-2,1f))
            box.addView(row);root.addView(box)
        }
    }

    private fun pairPc(qr:String){val s=session?:return;io.submit{runCatching{PairingClient(this,store).pair(qr,s,store.identity())}.onSuccess{pc->val p=store.pairs();p.removeAll{it.localDeviceId==pc.localDeviceId};p+=pc;store.savePairs(p);runOnUiThread{startListener();toast("Paired ${pc.name}");render()}}.onFailure{runOnUiThread{toast(it.message?:"Pairing failed")}}}}

    private fun approvePendingLocal(){
        val p=store.get("local_pending")?.let{runCatching{JSONObject(it)}.getOrNull()}?:return;store.remove("local_pending")
        if(p.optLong("expiresAtMs")<=System.currentTimeMillis()){toast("Unlock request expired");return}
        val pc=store.pairs().firstOrNull{it.localDeviceId==p.optString("deviceId")}?:return
        biometric("Unlock ${pc.name}"){ok->if(ok)io.submit{runCatching{LegacyProtocol.answerUnlock(p.getString("pcbuIP"),p.getInt("pcbuPort"),pc.localDeviceId,pc.encryptionKey,pc.passwordKey)}.onFailure{runOnUiThread{toast(it.message?:"Unlock failed")}}}}
    }

    private fun approvePendingCloud(){
        val pending=store.get("cloud_pending")?.let{runCatching{JSONObject(it)}.getOrNull()}?:return
        store.remove("cloud_pending")
        val pc=store.pairs().firstOrNull{it.localDeviceId==pending.optString("deviceId")}?:return
        val challenge=runCatching{JSONObject(pending.getString("challenge"))}.getOrNull()?:return
        biometric("Unlock ${pc.name}"){ok->io.submit{
            runCatching{if(ok)controller?.approve(pc,challenge)else controller?.deny(pc,challenge,"biometric_canceled")}
                .onFailure{runOnUiThread{toast(it.message?:"Remote unlock failed")}}
        }}
    }

    private fun biometric(title:String,done:(Boolean)->Unit){
        val allowed=BiometricManager.Authenticators.BIOMETRIC_STRONG
        if(BiometricManager.from(this).canAuthenticate(allowed)!=BiometricManager.BIOMETRIC_SUCCESS){done(false);return}
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){done(true)}
            override fun onAuthenticationError(errorCode:Int,errString:CharSequence){done(false)}
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Confirm PC login with fingerprint or face")
                .setAllowedAuthenticators(allowed)
                .setNegativeButtonText("Cancel")
                .build()
        )
    }
}
