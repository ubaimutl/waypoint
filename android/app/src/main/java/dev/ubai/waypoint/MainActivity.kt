package dev.ubai.waypoint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity:Activity() {
 private val green=Color.rgb(43,93,73)
 private val ink=Color.rgb(35,53,43)
 private val muted=Color.rgb(100,113,102)
 private val line=Color.rgb(224,230,220)
 private val handler=Handler(Looper.getMainLooper())
 private lateinit var root:LinearLayout
 private lateinit var serverValue:TextView
 private lateinit var connectionStatus:TextView
 private lateinit var modeHelp:TextView
 private lateinit var modeButton:Button
 private lateinit var locationStatus:TextView
 private lateinit var reportTime:TextView
 private lateinit var modeGroup:RadioGroup
 private val permissionValues=mutableMapOf<String,TextView>()
 private val modeIds=mutableMapOf<String,Int>()
 private var selectedMode="builtin"
 private val refresh=object:Runnable{override fun run(){updateStatus();handler.postDelayed(this,1000)}}
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
 private fun allowed(permission:String)=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED
 private fun background(c:Int,border:Boolean=false)=GradientDrawable().apply{setColor(c);cornerRadius=dp(16).toFloat();if(border)setStroke(dp(1),line)}

 override fun onCreate(saved:Bundle?) {
  super.onCreate(saved)
  val provision=File(filesDir,"provision.json")
  if(provision.exists()) {
   try{Config.save(this,JSONObject(provision.readText()));Config.status(this,"Connection configured. Send a test location to verify.")}
   catch(_:Exception){Config.status(this,"Could not import connection settings.")}
   finally{provision.delete()}
  }
  val scroll=ScrollView(this).apply{isFillViewport=true;setBackgroundColor(Color.rgb(244,246,242));clipToPadding=false}
  root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(24),dp(20),dp(24))}
  scroll.addView(root)
  scroll.setOnApplyWindowInsetsListener{v,insets->val bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets}
  setContentView(scroll)
  val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,0,0,dp(24))}
  root.addView(header)
  header.addView(ImageView(this).apply{setImageResource(R.drawable.ic_waypoint);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(44),dp(44)).apply{marginEnd=dp(12)})
  val titles=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};header.addView(titles,LinearLayout.LayoutParams(0,-2,1f))
  text(titles,"Waypoint",28,true,ink)
  text(titles,"Device locator",12,false,muted)

  val connection=card("Connection","Choose how this phone receives internet requests.")
  val serverRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(16),0,dp(16))};connection.addView(serverRow)
  val serverCopy=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};serverRow.addView(serverCopy,LinearLayout.LayoutParams(0,-2,1f))
  text(serverCopy,"Server",12,false,muted)
  serverValue=text(serverCopy,"No server configured",15,true,ink).apply{maxLines=2}
  button(serverRow,"Edit",false,true){connectionDialog()}.apply{layoutParams=LinearLayout.LayoutParams(dp(72),dp(48)).apply{marginStart=dp(12)}}
  connectionStatus=text(connection,"Internet connection off",15,true,green).apply{setPadding(dp(12),dp(12),dp(12),dp(12));background=background(Color.rgb(238,244,233))}
  text(connection,"Internet mode",12,false,muted).setPadding(0,dp(20),0,dp(8))
  modeGroup=RadioGroup(this).apply{orientation=LinearLayout.VERTICAL}
  connection.addView(modeGroup)
  for((mode,title) in listOf("builtin" to "Built-in connection","unifiedpush" to "UnifiedPush distributor","off" to "Internet off")) {
   val id=View.generateViewId();modeIds[mode]=id
   modeGroup.addView(RadioButton(this).apply{this.id=id;text=title;textSize=15f;setTextColor(ink);minHeight=dp(48);buttonTintList=android.content.res.ColorStateList.valueOf(green)},RadioGroup.LayoutParams(-1,dp(48)))
  }
  selectedMode=ConnectionModes.current(this).let{if(it=="off")"builtin" else it}
  modeGroup.check(modeIds.getValue(selectedMode))
  modeHelp=text(connection,"",12,false,muted).apply{setPadding(0,dp(8),0,dp(12))}
  modeButton=button(connection,"Connect",true){applyMode()}
  modeGroup.setOnCheckedChangeListener{_,id->selectedMode=modeIds.entries.first{it.value==id}.key;updateModeHelp()}
  updateModeHelp()

  val location=card("Location","Reports are sent only when requested.")
  locationStatus=text(location,"No request yet",15,false,ink).apply{setPadding(0,dp(16),0,dp(4))}
  reportTime=text(location,"",12,false,muted)
  button(location,"Send test location",true){locate(true)}
  button(location,"Check pending request"){locate(false)}

  val permissions=card("Permissions","Background location is needed for remote requests.")
  for(label in listOf("Location","Background location","Notifications")) {
   val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(12),0,dp(12))};permissions.addView(row)
   text(row,label,15,false,ink).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
   permissionValues[label]=text(row,"Not allowed",12,true,muted)
  }
  button(permissions,"Grant location & notifications"){
   val requested=mutableListOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)
   if(Build.VERSION.SDK_INT>=33)requested.add(Manifest.permission.POST_NOTIFICATIONS)
   requestPermissions(requested.toTypedArray(),10)
  }
  button(permissions,"Background location settings"){
   appSettings();Toast.makeText(this,"Permissions → Location → Allow all the time",Toast.LENGTH_LONG).show()
  }
  button(permissions,"App battery settings"){appSettings()}
  text(permissions,"Allow background activity for Waypoint. With UnifiedPush, also allow it for your distributor.",12,false,muted).setPadding(0,dp(12),0,0)

  val fallbacks=card("Fallbacks","Available with either internet mode, or on their own.")
  val periodic=Switch(this).apply{text="Check every 15 minutes";textSize=15f;setTextColor(ink);minHeight=dp(52);isChecked=getSharedPreferences("state",0).getBoolean("fallback",false)}
  fallbacks.addView(periodic,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(12)})
  periodic.setOnCheckedChangeListener{_,enabled->
   if(enabled && !allowed(Manifest.permission.ACCESS_BACKGROUND_LOCATION)){periodic.isChecked=false;Toast.makeText(this,"Allow background location first.",Toast.LENGTH_LONG).show();return@setOnCheckedChangeListener}
   getSharedPreferences("state",0).edit().putBoolean("fallback",enabled).apply();Scheduling.apply(this)
  }
  text(fallbacks,"Checks for a queued request. GPS stays off unless a request is pending. Android may delay checks.",12,false,muted)
  text(fallbacks,"SMS wake-up",15,true,ink).setPadding(0,dp(20),0,dp(8))
  text(fallbacks,"Copy a signed SMS from the dashboard and send it to this phone from another device. No SMS is sent automatically.",12,false,muted)
  button(fallbacks,"SMS permission"){
   if(allowed(Manifest.permission.RECEIVE_SMS))appSettings() else requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS),12)
  }
  button(root,"Open dashboard"){
   val url=runCatching{Config.read(this)?.optString("url")}.getOrNull()
   if(url.isNullOrEmpty())connectionDialog() else startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
  }
  syncIdentity()
  Scheduling.apply(this)
  if(ConnectionModes.current(this)=="unifiedpush")PushSetup.uploadSaved(this)
  if(ConnectionModes.current(this)=="builtin")runCatching{startForegroundService(Intent(this,InternetService::class.java).putExtra("ensure",true))}
 }
 private fun text(parent:LinearLayout,value:String,size:Int,bold:Boolean=false,color:Int=ink)=TextView(this).apply{
  text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(dp(3).toFloat(),1f);if(bold)setTypeface(null,Typeface.BOLD);parent.addView(this)
 }
 private fun card(title:String,description:String):LinearLayout {
  val view=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(20));background=background(Color.WHITE,true)}
  root.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(16)})
  text(view,title,20,true,ink)
  text(view,description,12,false,muted).setPadding(0,dp(8),0,0)
  return view
 }
 private fun button(parent:LinearLayout,label:String,primary:Boolean=false,compact:Boolean=false,action:()->Unit):Button {
  val b=Button(this).apply{text=label;isAllCaps=false;textSize=15f;minHeight=dp(48);setTextColor(if(primary)Color.WHITE else green);background=background(if(primary)green else Color.rgb(241,245,237));setPadding(dp(12),dp(8),dp(12),dp(8));setOnClickListener{action()}}
  parent.addView(b,LinearLayout.LayoutParams(-1,dp(48)).apply{if(!compact)topMargin=dp(12)})
  return b
 }
 private fun updateModeHelp() {
  modeHelp.text=when(selectedMode){
   "builtin"->"No extra app. Waypoint keeps a secure connection to your server and shows a persistent listening notification."
   "unifiedpush"->"Uses ntfy, Sunup, or another distributor. Waypoint itself does not keep a permanent connection."
   else->"Stops internet wake-ups. SMS and scheduled checks can still be used."
  }
  modeButton.text=when(selectedMode){"builtin"->if(ConnectionModes.current(this)=="builtin")"Reconnect built-in connection" else "Connect built-in connection";"unifiedpush"->"Register UnifiedPush";else->"Turn off internet connection"}
 }
 private fun applyMode() {
  if(selectedMode!="off" && runCatching{Config.read(this)}.getOrNull()==null){connectionDialog();return}
  if(selectedMode=="builtin" && Build.VERSION.SDK_INT>=33 && !allowed(Manifest.permission.POST_NOTIFICATIONS)){
   requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),13);Toast.makeText(this,"Allow notifications, then tap Connect.",Toast.LENGTH_LONG).show();return
  }
  try{when(selectedMode){"builtin"->ConnectionModes.startBuiltin(this);"unifiedpush"->PushSetup.register(this);else->ConnectionModes.stop(this)}}
  catch(e:Exception){Toast.makeText(this,e.message?:"Could not start connection.",Toast.LENGTH_LONG).show()}
  updateStatus()
 }
 private fun updateStatus() {
  if(!::serverValue.isInitialized)return
  val state=getSharedPreferences("state",0)
  val config=runCatching{Config.read(this)}.getOrNull()
  serverValue.text=config?.optString("url")?:"No server configured"
  connectionStatus.text=when(ConnectionModes.current(this)){
   "builtin"->"Built-in · "+state.getString("internetStatus","Not connected")
   "unifiedpush"->"UnifiedPush · "+state.getString("pushStatus","Not registered")
   else->"Internet connection off"
  }
  locationStatus.text=state.getString("status","Send a test location after connecting your server.")
  val last=state.getLong("updated",0)
  reportTime.text=if(last>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(last)) else ""
  permissionValues["Location"]?.text=if(allowed(Manifest.permission.ACCESS_FINE_LOCATION))"Precise" else if(allowed(Manifest.permission.ACCESS_COARSE_LOCATION))"Approximate" else "Not allowed"
  permissionValues["Background location"]?.text=if(allowed(Manifest.permission.ACCESS_BACKGROUND_LOCATION))"Allowed" else "Not allowed"
  permissionValues["Notifications"]?.text=if(Build.VERSION.SDK_INT<33 || allowed(Manifest.permission.POST_NOTIFICATIONS))"Allowed" else "Not allowed"
  updateModeHelp()
 }
 private fun syncIdentity() {
  Thread {runCatching {
   Config.read(this)?.let {config->
    val result=Tracker.api(config,"/api/device/identity",JSONObject().put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL))
    getSharedPreferences("state",0).edit().putString("deviceName",result.getString("name")).apply()
   }
  }}.start()
 }
 private fun appSettings(){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
 private fun locate(manual:Boolean) {
  if(!allowed(Manifest.permission.ACCESS_FINE_LOCATION) && !allowed(Manifest.permission.ACCESS_COARSE_LOCATION)){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),11);return}
  try{Config.status(this,"Starting location request…");startForegroundService(Intent(this,LocateService::class.java).putExtra("manual",manual))}
  catch(_:Exception){Config.status(this,"Could not start location. Check permissions.")}
 }
 override fun onResume(){super.onResume();handler.post(refresh)}
 override fun onPause(){handler.removeCallbacks(refresh);super.onPause()}
 private fun connectionDialog() {
  val saved=runCatching{Config.read(this)}.getOrNull()?:JSONObject()
  val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(12))}
  fun field(label:String,value:String,secret:Boolean=false):EditText {
   text(content,label,12,false,muted).setPadding(0,dp(12),0,dp(4))
   return EditText(this).apply{setText(value);textSize=15f;inputType=if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;setSingleLine(true);content.addView(this,LinearLayout.LayoutParams(-1,dp(48)))}
  }
  val url=field("HTTPS server URL",saved.optString("url"))
  val token=field("Device token",saved.optString("token"),true)
  text(content,"Use the device token from your server configuration, not your dashboard password.",12,false,muted).setPadding(0,dp(8),0,dp(8))
  val proxy=CheckBox(this).apply{text="Use Cloudflare Access (optional)";textSize=15f;minHeight=dp(48);setTextColor(ink);isChecked=saved.optString("cfId").isNotEmpty()};content.addView(proxy)
  val proxyFields=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};content.addView(proxyFields)
  fun proxyField(hint:String,value:String)=EditText(this).apply{this.hint=hint;setText(value);setSingleLine(true);textSize=15f;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;proxyFields.addView(this,LinearLayout.LayoutParams(-1,dp(48)))}
  val id=proxyField("Service token ID",saved.optString("cfId"))
  val secret=proxyField("Service token secret",saved.optString("cfSecret"))
  proxyFields.visibility=if(proxy.isChecked)View.VISIBLE else View.GONE
  proxy.setOnCheckedChangeListener{_,checked->proxyFields.visibility=if(checked)View.VISIBLE else View.GONE}
  val scroll=ScrollView(this).apply{addView(content)}
  val dialog=AlertDialog.Builder(this).setTitle("Server connection").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
  dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
   try{
    val next=JSONObject().put("url",url.text.toString().trim().trimEnd('/')).put("token",token.text.toString().trim()).put("cfId",if(proxy.isChecked)id.text.toString().trim() else "").put("cfSecret",if(proxy.isChecked)secret.text.toString().trim() else "")
    Config.validate(next)
    val same=saved.optString("url")==next.getString("url") && saved.optString("token")==next.getString("token")
    if(same){saved.optJSONObject("pushSubscription")?.let{next.put("pushSubscription",it)};next.put("pushEnabled",saved.optBoolean("pushEnabled",true))}
    else if(saved.has("token"))ConnectionModes.stop(this)
    Config.save(this,next)
    if(same && ConnectionModes.current(this)=="builtin")startForegroundService(Intent(this,InternetService::class.java))
    Config.status(this,"Server settings saved.");syncIdentity();dialog.dismiss();updateStatus()
   }catch(e:Exception){Toast.makeText(this,e.message?:"Could not save settings.",Toast.LENGTH_LONG).show()}
  }}
  dialog.show()
 }
}
