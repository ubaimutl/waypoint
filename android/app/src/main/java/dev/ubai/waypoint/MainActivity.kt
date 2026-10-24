package dev.ubai.waypoint
import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
class MainActivity:Activity() {
 private lateinit var container:LinearLayout
 private lateinit var status:TextView
 private lateinit var details:TextView
 private val handler=Handler(Looper.getMainLooper())
 private val green=Color.rgb(43,93,73)
 private val refresh=object:Runnable{override fun run(){updateStatus();handler.postDelayed(this,1000)}}
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 override fun onCreate(state:Bundle?){
  super.onCreate(state)
  val provision=File(filesDir,"provision.json")
  if(provision.exists()){
   try{Config.save(this,JSONObject(provision.readText()));Config.status(this,"Connected. Send a test location to verify setup.")}
   catch(_:Exception){Config.status(this,"Could not import connection settings.")}
   finally{provision.delete()}
  }
  val scroll=ScrollView(this).apply{setBackgroundColor(Color.rgb(245,246,243));isFillViewport=true}
  container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(25),dp(28),dp(25),dp(24))}
  scroll.addView(container)
  scroll.setOnApplyWindowInsetsListener{v,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
  setContentView(scroll)
  text("Waypoint",30,true).setTextColor(green)
  text("Device locator",17).setPadding(0,dp(7),0,dp(25))
  details=text("",14)
  text("Location requests",22,true).setPadding(0,dp(30),0,dp(10))
  text("Internet push wakes the app to fetch pending commands from your server. SMS and optional periodic checks are fallbacks.",15)
  status=text("",15).apply{setPadding(dp(17),dp(17),dp(17),dp(17));background=shape(Color.WHITE);layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(22);bottomMargin=dp(10)}}
  button("Register internet push",true){PushSetup.register(this)}
  button("Disable internet push"){PushSetup.disable(this)}
  button("Send test location"){locate(true)}
  button("Check pending request"){locate(false)}
  button("Allow location, SMS & notifications"){requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.RECEIVE_SMS,Manifest.permission.POST_NOTIFICATIONS),10)}
  button("Background battery settings"){startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))}
  button("Background location permission"){
   startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
   Toast.makeText(this,"Permissions → Location → Allow all the time",Toast.LENGTH_LONG).show()
  }
  val fallback=Switch(this).apply{
   text="Check server every 15 minutes";textSize=14f;setTextColor(Color.rgb(63,80,68));setPadding(0,dp(17),0,dp(12))
   isChecked=getSharedPreferences("state",0).getBoolean("fallback",false)
   setOnCheckedChangeListener{_,enabled->
    if(enabled && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED){isChecked=false;Toast.makeText(this@MainActivity,"Allow background location first.",Toast.LENGTH_LONG).show();return@setOnCheckedChangeListener}
    getSharedPreferences("state",0).edit().putBoolean("fallback",enabled).apply();Scheduling.apply(this@MainActivity)
   }
  };container.addView(fallback)
  text("Optional fallback. Checks for requests without using GPS unless one is pending. Android may delay checks while sleeping.",12)
  button("Connection settings"){connectionDialog()}
  button("Open dashboard"){val url=runCatching{Config.read(this)?.optString("url")}.getOrNull();if(url.isNullOrEmpty())Toast.makeText(this,"Configure your server first.",Toast.LENGTH_LONG).show() else startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
  text("SMS wake-up: request a location on your dashboard, copy the signed SMS, then send it to this phone from another device. No SMS is sent automatically.",12).setPadding(0,dp(24),0,dp(8))
  Scheduling.apply(this)
  PushSetup.uploadSaved(this)
 }
 override fun onResume(){super.onResume();handler.post(refresh)}
 override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
 private fun shape(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(12).toFloat()}
 private fun text(value:String,size:Int,bold:Boolean=false):TextView=TextView(this).apply{text=value;textSize=size.toFloat();setTextColor(Color.rgb(83,100,83));setLineSpacing(dp(4).toFloat(),1f);if(bold)setTypeface(null,Typeface.BOLD);container.addView(this)}
 private fun button(label:String,primary:Boolean=false,action:()->Unit){
  val b=Button(this).apply{text=label;isAllCaps=false;textSize=14f;setTextColor(if(primary)Color.WHITE else green);background=shape(if(primary)green else Color.WHITE);setPadding(dp(13),dp(12),dp(13),dp(12));setOnClickListener{action()}}
  container.addView(b,LinearLayout.LayoutParams(-1,dp(52)).apply{topMargin=dp(10)})
 }
 private fun updateStatus(){
  if(!::status.isInitialized)return
  val p=getSharedPreferences("state",0);val last=p.getLong("updated",0)
  status.text=p.getString("status","Connect the app and allow permissions to begin.")+(if(last>0)"\n"+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(last)) else "")
  val config=runCatching{Config.read(this)}.getOrNull()
  val fine=checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
  val bg=checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED
  val sms=checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED
  details.text=(config?.optString("url")?:"Server not connected")+"\nPush: "+p.getString("pushStatus","Not registered")+"\nLocation: ${if(fine)"precise" else "needs permission"} · Background: ${if(bg)"allowed" else "not allowed"}\nSMS wake-up: ${if(sms)"ready" else "needs permission"}"
 }
 private fun locate(manual:Boolean){
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),11);return}
  try{startForegroundService(Intent(this,LocateService::class.java).putExtra("manual",manual))}catch(_:Exception){Config.status(this,"Could not start location. Check permissions.")}
 }
 private fun connectionDialog(){
  val saved=runCatching{Config.read(this)}.getOrNull()?:JSONObject()
  val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),0)}
  fun field(hint:String,value:String,secret:Boolean=false)=EditText(this).apply{this.hint=hint;setText(value);textSize=14f;inputType=if(secret)129 else 17;setSingleLine(true);layout.addView(this)}
  val url=field("HTTPS server URL",saved.optString("url",""))
  val token=field("Device API token",saved.optString("token"),true)
  val proxy=Button(this).apply{text="Optional Cloudflare Access settings";isAllCaps=false;layout.addView(this)}
  val cfid=field("Cloudflare service token ID (optional)",saved.optString("cfId"))
  val secret=field("Cloudflare service token secret (optional)",saved.optString("cfSecret"),true)
  cfid.visibility=View.GONE;secret.visibility=View.GONE
  proxy.setOnClickListener{val visible=cfid.visibility==View.GONE;cfid.visibility=if(visible)View.VISIBLE else View.GONE;secret.visibility=cfid.visibility}
  val dialog=android.app.AlertDialog.Builder(this).setTitle("Connection settings").setView(layout).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
  dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   try{val next=JSONObject().put("url",url.text.toString().trim().trimEnd('/')).put("token",token.text.toString().trim()).put("cfId",cfid.text.toString().trim()).put("cfSecret",secret.text.toString().trim());if(saved.optString("url")==next.optString("url") && saved.optString("token")==next.optString("token"))saved.optJSONObject("pushSubscription")?.let{next.put("pushSubscription",it)} else if(saved.has("token"))PushSetup.disable(this);Config.save(this,next);PushSetup.state(this,"Register internet push for this connection.");Config.status(this,"Settings saved. Send a test location to verify.");dialog.dismiss()}
   catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}
  }};dialog.show()
 }
}
