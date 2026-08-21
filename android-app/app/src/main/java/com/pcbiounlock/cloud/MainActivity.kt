package com.pcbiounlock.cloud

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.TOP; setPadding(dp(20),dp(20),dp(20),dp(36)); setBackgroundColor(BG) }
        setContentView(ScrollView(this).apply { isFillViewport=true; setBackgroundColor(BG); addView(root) })
        restoreSession()
        render()
        if(Build.VERSION.SDK_INT >= 33) root.post { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        if(intent.getBooleanExtra("approve_local", false)) approvePendingLocal()
        if(intent.getBooleanExtra("approve_cloud", false)) approvePendingCloud()
    }

    override fun onDestroy() { controller?.close(); io.shutdownNow(); super.onDestroy() }

    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun shape(color:Int,radius:Int=18,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();stroke?.let{setStroke(dp(1),it)}}
    private fun text(value: String, size: Float = 16f, color:Int=TEXT) = TextView(this).apply { this.text=value; textSize=size; setTextColor(color); setPadding(0,dp(4),0,dp(4)) }
    private fun heading(value:String,size:Float=22f)=text(value,size).apply{setTypeface(typeface,Typeface.BOLD)}
    private fun space(h:Int)=Space(this).apply{layoutParams=LinearLayout.LayoutParams(1,dp(h))}
    private fun field(hint: String, secret: Boolean=false) = EditText(this).apply {
        this.hint=hint; setHintTextColor(MUTED); setTextColor(TEXT); textSize=16f; setPadding(dp(16),dp(12),dp(16),dp(12)); background=shape(SURFACE,14,BORDER)
        if(secret) inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    private fun button(label: String, primary:Boolean=true, f:()->Unit)=Button(this).apply {
        text=label; isAllCaps=false; textSize=15f; setTypeface(typeface,Typeface.BOLD); setTextColor(if(primary)Color.WHITE else TEXT)
        backgroundTintList=ColorStateList.valueOf(if(primary)ACCENT else SURFACE_2); setPadding(dp(16),dp(8),dp(16),dp(8)); setOnClickListener{f()}
    }
    private fun toast(v:String)=Toast.makeText(this,v,Toast.LENGTH_LONG).show()

    private fun render() {
        root.removeAllViews()
        val brand=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        brand.addView(ImageView(this).apply{setImageResource(R.drawable.app_logo);scaleType=ImageView.ScaleType.CENTER_INSIDE},LinearLayout.LayoutParams(dp(64),dp(64)))
        brand.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,0,0);addView(heading("PC Bio Unlock",26f));addView(text("Secure biometric access",14f,MUTED))},LinearLayout.LayoutParams(0,-2,1f))
        root.addView(brand);root.addView(space(22))
        val s=session
        if(s==null) renderLogin() else renderDashboardV2(s)
    }

    private fun renderLogin() {
        val defaultRelay = "https://pcbu-relay.advsolar.workers.dev"
        root.addView(heading("Welcome",24f));root.addView(text("Sign in to connect and control your trusted PCs.",15f,MUTED));root.addView(space(18))
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));background=shape(CARD,20,BORDER)}
        val url=field(defaultRelay).apply{setText(store.get("base_url")?:defaultRelay)}
        val email=field("User ID"); val password=field("Password (12+ characters)",true)
        card.addView(text("Relay address",13f,MUTED));card.addView(url);card.addView(space(12));card.addView(email);card.addView(space(12));card.addView(password);card.addView(space(18))
        card.addView(button("Log in"){login(url.text.toString(),email.text.toString(),password.text.toString(),false)});card.addView(space(8))
        card.addView(button("Create profile",false){login(url.text.toString(),email.text.toString(),password.text.toString(),true)})
        root.addView(card)
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
            row.addView(button("Lock PC"){lockPc(pc)},LinearLayout.LayoutParams(0,-2,1f))
            box.addView(row);root.addView(box)
        }
    }

    private fun renderDashboardV2(s:CloudSession) {
        val biometricReady=BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)==BiometricManager.BIOMETRIC_SUCCESS
        val status=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(14));background=shape(if(biometricReady)SUCCESS_BG else WARNING_BG,16)}
        status.addView(heading(if(biometricReady)"Fingerprint / face ready" else "Biometric setup required",17f))
        status.addView(text(if(biometricReady)"Unlock approvals are protected by strong biometrics." else "Enroll a fingerprint or secure face unlock in Android settings.",14f,MUTED))
        root.addView(status);root.addView(space(18))

        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(button("Pair PC"){qrLauncher.launch(ScanOptions().setPrompt("Scan the Internet relay QR").setBeepEnabled(false))},LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(space(10),LinearLayout.LayoutParams(dp(10),1))
        actions.addView(button("Log out",false){controller?.close();controller=null;stopService(Intent(this,UnlockListenerService::class.java));session=null;store.remove("cloud_session");render()})
        root.addView(actions);root.addView(space(26));root.addView(heading("My systems",23f))
        root.addView(text("Lock remotely, then approve Windows unlock from your notification.",14f,MUTED));root.addView(space(10))

        val pcs=store.pairs()
        if(pcs.isEmpty()) root.addView(text("No PC paired yet. Tap Pair PC to get started.",16f,MUTED))
        pcs.forEach{pc->
            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(16));background=shape(CARD,20,BORDER)}
            box.addView(heading(pc.name,19f));box.addView(text(pc.userName,14f,MUTED));box.addView(space(8))
            box.addView(text(if(pc.cloudDeviceId.isBlank())"Local only - pair again with Internet relay" else "Online relay ready",14f,if(pc.cloudDeviceId.isBlank())WARNING else SUCCESS))
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(button("Lock PC"){lockPc(pc)},LinearLayout.LayoutParams(0,-2,1f));row.addView(space(8),LinearLayout.LayoutParams(dp(8),1))
            row.addView(button("Remove",false){val all=store.pairs();all.removeAll{it.localDeviceId==pc.localDeviceId};store.savePairs(all);render()})
            box.addView(space(14));box.addView(row);root.addView(box);root.addView(space(12))
        }
    }

    private fun lockPc(pc: PairedPc) {
        val a=api?:return; val s=session?:return
        if(pc.cloudDeviceId.isBlank()){toast("This PC is local-only. Pair it again using Internet relay.");return}
        io.submit { runCatching {
            val command=JSONObject().put("action","LOCK").put("deviceId",pc.localDeviceId)
            val encrypted=LegacyProtocol.hexEncode(LegacyProtocol.encryptPacket(command.toString().toByteArray(),pc.encryptionKey))
            a.sendCommand(s.accountToken,pc.cloudDeviceId,JSONObject().put("deviceId",pc.localDeviceId).put("encData",encrypted).toString())
        }.onSuccess{runOnUiThread{toast("Lock command sent to ${pc.name}")}}
            .onFailure{runOnUiThread{toast(it.message?:"Could not lock PC")}} }
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
        if(BiometricManager.from(this).canAuthenticate(allowed)!=BiometricManager.BIOMETRIC_SUCCESS){toast("Set up a strong fingerprint or face unlock in Android settings first.");done(false);return}
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

    companion object {
        private val BG=Color.rgb(11,16,32); private val CARD=Color.rgb(20,28,48); private val SURFACE=Color.rgb(27,37,61); private val SURFACE_2=Color.rgb(42,53,78)
        private val TEXT=Color.rgb(245,247,255); private val MUTED=Color.rgb(163,174,198); private val BORDER=Color.rgb(52,65,94); private val ACCENT=Color.rgb(224,132,39)
        private val SUCCESS=Color.rgb(70,211,151); private val SUCCESS_BG=Color.rgb(20,61,53); private val WARNING=Color.rgb(255,190,92); private val WARNING_BG=Color.rgb(76,53,24)
    }
}
