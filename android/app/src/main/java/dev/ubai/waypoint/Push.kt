package dev.ubai.waypoint

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object PushSetup {
 private val uploading=AtomicBoolean(false)
 fun instance(config:JSONObject):String = "waypoint-"+MessageDigest.getInstance("SHA-256").digest((config.getString("url")+config.getString("token")).toByteArray()).take(8).joinToString(""){"%02x".format(it)}
 fun state(context:Context,text:String) {context.getSharedPreferences("state",0).edit().putString("pushStatus",text).apply()}
 fun register(activity:Activity) {
  ConnectionModes.set(activity,"unifiedpush");activity.stopService(Intent(activity,InternetService::class.java));ConnectionModes.notifyServer(activity,"unifiedpush")
  Thread {
   try {
    val config=Config.read(activity)?:error("Configure your server first.")
    val key=Tracker.api(config,"/api/device/push-info").getString("publicKey")
    config.put("pushEnabled",true);Config.save(activity,config)
    activity.runOnUiThread {
     val distributors=UnifiedPush.getDistributors(activity)
     if(distributors.isEmpty()) {state(activity,"Install a UnifiedPush distributor such as ntfy or Sunup, then register again.");return@runOnUiThread}
     fun choose(distributor:String) {
      UnifiedPush.saveDistributor(activity,distributor)
      state(activity,"Registering internet push…")
      UnifiedPush.register(activity,instance=instance(config),messageForDistributor="Waypoint location requests",vapid=key)
     }
     if(distributors.size==1)choose(distributors[0])
     else {
      val labels=distributors.map {runCatching{activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(it,0)).toString()}.getOrDefault(it)}
      AlertDialog.Builder(activity).setTitle("Push distributor").setItems(labels.toTypedArray()){_,index->choose(distributors[index])}.setNegativeButton("Cancel",null).show()
     }
    }
   }catch(e:Exception){state(activity,e.message?:"Push registration failed.")}
  }.start()
 }
 fun uploadSaved(context:Context) {
  if(ConnectionModes.current(context)!="unifiedpush")return
  if(!uploading.compareAndSet(false,true))return
  Thread {
   try {
    val config=Config.read(context)?:return@Thread
    val saved=config.optJSONObject("pushSubscription")?:return@Thread
    Tracker.api(config,"/api/device/transport",JSONObject().put("mode","unifiedpush"))
    Tracker.api(config,"/api/device/push-subscription",saved)
    state(context,"Internet push registered")
   }catch(e:Exception){state(context,"Push registration not saved: "+(e.message?:"connection failed"))}
   finally{uploading.set(false)}
  }.start()
 }
 fun disable(context:Context) {
  val config=runCatching{Config.read(context)}.getOrNull()?:return
  UnifiedPush.unregister(context,instance(config))
  config.remove("pushSubscription");config.put("pushEnabled",false);Config.save(context,config)
  state(context,"Internet push disabled")
  Thread {runCatching{Tracker.api(config,"/api/device/push-unregister",JSONObject())}}.start()
 }
}
class PushHandler:PushService() {
 private fun current(instance:String)=runCatching{Config.read(this)?.let{PushSetup.instance(it)==instance && it.optBoolean("pushEnabled",true) && ConnectionModes.current(this)=="unifiedpush"}==true}.getOrDefault(false)
 override fun onNewEndpoint(endpoint:PushEndpoint,instance:String) {
  if(!current(instance))return
  val keys=endpoint.pubKeySet?:run{PushSetup.state(this,"Distributor did not provide encryption keys. Update it and register again.");return}
  val config=Config.read(this)?:return
  config.put("pushSubscription",JSONObject().put("endpoint",endpoint.url).put("keys",JSONObject().put("p256dh",keys.pubKey).put("auth",keys.auth)))
  Config.save(this,config)
  PushSetup.uploadSaved(this)
 }
 override fun onMessage(message:PushMessage,instance:String) {
  if(!current(instance) || !message.decrypted)return
  val data=runCatching{JSONObject(String(message.content))}.getOrNull()?:return
  if(data.optString("type")!="wake" || data.optInt("version")!=1)return
  val state=getSharedPreferences("state",0)
  if(System.currentTimeMillis()-state.getLong("lastPush",0)<10000)return
  state.edit().putLong("lastPush",System.currentTimeMillis()).apply()
  Config.status(this,"Internet push received. Fetching server command…")
  try {startForegroundService(Intent(this,LocateService::class.java))}
  catch(_:Exception){Config.status(this,"Push arrived, but Android blocked location. Open Waypoint and check background permissions.")}
 }
 override fun onRegistrationFailed(reason:FailedReason,instance:String) {if(current(instance))PushSetup.state(this,"Push registration failed: ${reason.name}")}
 override fun onUnregistered(instance:String) {
  if(!current(instance))return
  val config=Config.read(this)?:return
  config.remove("pushSubscription");Config.save(this,config)
  PushSetup.state(this,"Push unregistered. Register again.")
  Thread{runCatching{Tracker.api(config,"/api/device/push-unregister",JSONObject())}}.start()
 }
 override fun onTempUnavailable(instance:String){if(current(instance))PushSetup.state(this,"Push provider temporarily unavailable")}
}
