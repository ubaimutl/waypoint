package dev.ubai.waypoint

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

object Tracker {
 private val busy=AtomicBoolean(false)
 fun api(config:JSONObject,path:String,body:JSONObject?=null):JSONObject {
  val conn=URL(config.getString("url").trimEnd('/')+path).openConnection() as HttpsURLConnection
  try {
   conn.connectTimeout=15000;conn.readTimeout=20000;conn.instanceFollowRedirects=false
   conn.setRequestProperty("Authorization","Bearer "+config.getString("token"))
   if(config.optString("cfId").isNotEmpty()) { conn.setRequestProperty("CF-Access-Client-Id",config.getString("cfId"));conn.setRequestProperty("CF-Access-Client-Secret",config.optString("cfSecret")) }
   if(body!=null) { conn.requestMethod="POST";conn.doOutput=true;conn.setRequestProperty("Content-Type","application/json");conn.outputStream.use { it.write(body.toString().toByteArray()) } }
   val code=conn.responseCode
   if(code !in 200..299) throw IllegalStateException(when(code) { 301,302,303,307,308 -> "Server redirected the request. Check the server URL and optional access proxy credentials.";401,403 -> "Authentication failed. Check the device token and optional access proxy credentials.";409 -> "The request expired or was cancelled. Request location again.";else -> "Server returned HTTP $code. Try again when connected." })
   if(!conn.contentType.orEmpty().contains("application/json")) throw IllegalStateException("Expected JSON. Check your server and optional access proxy settings.")
   return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
  } finally { conn.disconnect() }
 }
 fun locate(context:Context,manual:Boolean=false,expected:String?=null,cancelled:AtomicBoolean=AtomicBoolean(false)) {
  if(!busy.compareAndSet(false,true)) return
  try {
   val config=Config.read(context)?:throw IllegalStateException("Connect your server first.")
   val command=api(config,"/api/device/commands").optJSONObject("command")
   if(expected!=null && command?.optString("id")!=expected) { Config.status(context,"Wake-up ignored: request is no longer pending.");return }
   if(command==null && !manual) { Config.status(context,"Checked for requests. None pending.");return }
   if(cancelled.get()) return
   if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Allow location access first.")
   val manager=context.getSystemService(LocationManager::class.java)
   if(!manager.isLocationEnabled) throw IllegalStateException("Turn on Location in Android settings.")
   Config.status(context,"Getting a fresh location…")
   val result=CompletableFuture<Location>()
   val listeners=mutableListOf<LocationListener>()
   val executor=Executors.newSingleThreadExecutor()
   try {
    for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER)) {
     if(manager.isProviderEnabled(provider)) {
      val listener=LocationListener { fix ->
       if(SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos<120_000_000_000L && (command==null || fix.time>=Instant.parse(command.getString("created_at")).toEpochMilli()-5000)) result.complete(fix)
      }
      listeners.add(listener)
      manager.requestLocationUpdates(provider,LocationRequest.Builder(1000).setDurationMillis(65000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(),executor,listener)
     }
    }
    if(listeners.isEmpty()) throw IllegalStateException("No location provider is enabled.")
    val deadline=SystemClock.elapsedRealtime()+65000
    while(!result.isDone && SystemClock.elapsedRealtime()<deadline && !cancelled.get()) Thread.sleep(250)
    if(cancelled.get()) return
    if(!result.isDone) throw IllegalStateException("No fresh fix in 65 seconds. Move near a window or outdoors and retry.")
    val fix=result.get()
    val battery=context.registerReceiver(null,IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level=battery?.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)?:-1
    val scale=battery?.getIntExtra(BatteryManager.EXTRA_SCALE,100)?:100
    val state=battery?.getIntExtra(BatteryManager.EXTRA_STATUS,-1)
    if(level<0 || scale<=0) throw IllegalStateException("Battery status unavailable.")
    val report=JSONObject().put("lat",fix.latitude).put("lng",fix.longitude).put("accuracy",fix.accuracy.toDouble()).put("battery",(level*100/scale).coerceIn(0,100)).put("charging",state==BatteryManager.BATTERY_STATUS_CHARGING || state==BatteryManager.BATTERY_STATUS_FULL).put("recorded_at",Instant.ofEpochMilli(fix.time).toString())
    if(command!=null) report.put("command_id",command.getString("id"))
    api(config,"/api/device/report",report)
    Config.status(context,"Location sent successfully · ± ${fix.accuracy.toInt()} m")
   } finally { listeners.forEach { manager.removeUpdates(it) };executor.shutdownNow() }
  } catch(e:Exception) { Config.status(context,e.message?:"Could not report location. Try again.") }
  finally { busy.set(false) }
 }
}

class LocateService:Service() {
 private val cancelled=AtomicBoolean(false)
 private var running=false
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(running) return START_NOT_STICKY
  running=true
  val manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("locating","Location requests",NotificationManager.IMPORTANCE_LOW))
  val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(this,"locating").setSmallIcon(dev.ubai.waypoint.R.drawable.ic_waypoint).setContentTitle("Waypoint is locating this phone").setContentText("Sending a location report to your private server.").setContentIntent(open).build()
  try { startForeground(1,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
  catch(e:Exception) { Config.status(this,"Location permission is required. Open Waypoint to finish setup.");stopSelf();return START_NOT_STICKY }
  Thread { Tracker.locate(this,intent?.getBooleanExtra("manual",false)==true,intent?.getStringExtra("command"),cancelled);stopSelf() }.start()
  return START_NOT_STICKY
 }
 override fun onDestroy() { cancelled.set(true);super.onDestroy() }
}
